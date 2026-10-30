package com.blessrom.commerce;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CommercialTest {
    @Inject CatalogService catalog;
    @Inject InventoryService inventory;
    @Inject ShippingService shipping;
    @Inject CartService carts;
    @Inject OrderService orders;
    @Inject Db db;
    record Fixture(UUID product,UUID variant,UUID category,String sku,String district) {}
    record Basket(UUID id,String token,Api.Checkout request) {}
    Api.Draft draft(UUID category,String sku,String price) {
        return new Api.Draft("Polo de prueba","Algodón",category,"https://example.com/polo.jpg",
            List.of(new Api.Variant(sku,Map.of("talla","M","color","Azul"),new BigDecimal(price),true)));
    }
    Fixture fixture(int stock) {
        String suffix=UUID.randomUUID().toString();
        UUID category=(UUID)catalog.createCategory(new Api.Category("Ropa","ropa-"+suffix)).get("id");
        String sku="SKU-"+suffix;
        UUID product=(UUID)catalog.create(new Api.CreateProduct("polo-"+suffix,draft(category,sku,"50.00"))).get("id");
        catalog.publish(product,0);
        UUID variant=Db.id(db.one("SELECT id FROM variants WHERE sku=?",sku),"id");
        if(stock>0) inventory.adjust(variant,new Api.Adjustment(0,stock,"Stock inicial de prueba"),"test");
        String district;
        do { district=String.valueOf(800000+ThreadLocalRandom.current().nextInt(100000)); }
        while(!db.rows("SELECT id FROM shipping_rates WHERE district_code=?",district).isEmpty());
        shipping.create(new Api.Rate("Departamento de prueba","Provincia de prueba",district,"Distrito de prueba",new BigDecimal("10.00"),true));
        return new Fixture(product,variant,category,sku,district);
    }
    Basket basket(Fixture f,int qty) {
        var cart=carts.create();UUID id=(UUID)cart.get("id");String token=(String)cart.get("accessToken");
        carts.set(id,token,f.variant,qty);
        var quote=carts.quote(id,token,f.district);
        return new Basket(id,token,new Api.Checkout("Cliente de prueba","cliente@example.com","999999999","Dirección de prueba",f.district,quote.get("fingerprint").toString()));
    }
    @Test void draftsStayPrivateAndPublishedRevisionsAreImmutable() {
        var f=fixture(4);
        long revisions=((Number)db.one("SELECT count(*) AS n FROM product_revisions WHERE product_id=?",f.product).get("n")).longValue();
        catalog.edit(f.product,new Api.UpdateDraft(1,draft(f.category,f.sku,"70.00")));
        given().get("/api/v1/products/"+f.product).then().statusCode(200).body("content.variants[0].price",equalTo("50.00"));
        assertThrows(Problem.class,()->catalog.publish(f.product,1));
        catalog.publish(f.product,2);
        given().get("/api/v1/products/"+f.product).then().statusCode(200).body("content.variants[0].price",equalTo("70.00"));
        assertEquals(revisions+1,((Number)db.one("SELECT count(*) AS n FROM product_revisions WHERE product_id=?",f.product).get("n")).longValue());
        catalog.archive(f.product,3);
        given().get("/api/v1/products/"+f.product).then().statusCode(404);
    }
    @Test void checkoutIsIdempotentAndSnapshotsAreHistorical() {
        var f=fixture(5);var b=basket(f,2);
        var first=orders.checkout(b.id,b.token,"checkout-key-001",b.request);
        UUID order=(UUID)first.order().get("id");
        assertFalse(first.replayed());assertEquals(new BigDecimal("110.00"),first.order().get("total"));
        assertEquals(order,orders.checkout(b.id,b.token,"checkout-key-001",b.request).order().get("id"));
        assertEquals(2,inventory.get(f.variant).get("reserved"));
        Problem conflict=assertThrows(Problem.class,()->orders.checkout(b.id,b.token,"checkout-key-002",b.request));
        assertEquals("IDEMPOTENCY_CONFLICT",conflict.code);
        catalog.edit(f.product,new Api.UpdateDraft(1,draft(f.category,f.sku,"99.00")));
        catalog.publish(f.product,2);
        given().header("X-Cart-Token",b.token).get("/api/v1/orders/"+order).then().statusCode(200)
            .body("total",equalTo(110.0f)).body("items[0].unit_price",equalTo(50.0f))
            .body("$",not(hasKey("token_hash"))).body("$",not(hasKey("request_hash")));
    }
    @Test void changedQuotesRequireReconfirmation() {
        var f=fixture(3);var b=basket(f,1);
        catalog.edit(f.product,new Api.UpdateDraft(1,draft(f.category,f.sku,"65.00")));catalog.publish(f.product,2);
        Problem error=assertThrows(Problem.class,()->orders.checkout(b.id,b.token,"checkout-quote-1",b.request));
        assertEquals("QUOTE_CHANGED",error.code);assertEquals(0,inventory.get(f.variant).get("reserved"));
        assertTrue(db.rows("SELECT id FROM orders WHERE cart_id=?",b.id).isEmpty());
    }
    @Test void ownershipAndQuantitiesAreEnforcedOverHttp() {
        var f=fixture(4);var b=basket(f,1);
        given().header("X-Cart-Token",b.token).get("/api/v1/carts/"+b.id).then().statusCode(200).header("Cache-Control","no-store");
        given().get("/api/v1/carts/"+b.id).then().statusCode(404);
        given().header("X-Cart-Token","wrong-token").get("/api/v1/carts/"+b.id).then().statusCode(404);
        given().header("X-Cart-Token",b.token).contentType("application/json").body(Map.of("quantity",-1))
            .put("/api/v1/carts/"+b.id+"/items/"+f.variant).then().statusCode(400);
        var order=orders.checkout(b.id,b.token,"ownership-test-1",b.request).order();
        given().header("X-Cart-Token","wrong-token").get("/api/v1/orders/"+order.get("id")).then().statusCode(404);
        given().header("X-Cart-Token","wrong-token").post("/api/v1/orders/"+order.get("id")+"/cancel").then().statusCode(404);
        assertEquals(1,inventory.get(f.variant).get("reserved"));
    }
    @Test void cancellationReleasesExactlyOnceAndProtectsReservedStock() {
        var f=fixture(2);var b=basket(f,2);
        UUID order=(UUID)orders.checkout(b.id,b.token,"cancel-test-001",b.request).order().get("id");
        long version=Db.version(inventory.get(f.variant));
        assertThrows(Problem.class,()->inventory.adjust(f.variant,new Api.Adjustment(version,-1,"No permitido"),"test"));
        assertEquals("CANCELLED",orders.cancel(order,b.token).get("status"));
        orders.cancel(order,b.token);
        assertEquals(0,inventory.get(f.variant).get("reserved"));
        assertEquals(2,inventory.get(f.variant).get("on_hand"));
        assertEquals(3,inventory.movements(f.variant,0,20).size());
    }
    @Test void expiryIsDurableAndIdempotent() {
        var f=fixture(2);var b=basket(f,1);
        UUID order=(UUID)orders.checkout(b.id,b.token,"expire-test-001",b.request).order().get("id");
        QuarkusTransaction.requiringNew().run(()->db.execute("UPDATE orders SET reservation_expires_at=now()-interval '1 minute' WHERE id=?",order));
        orders.expire(order);orders.expire(order);
        assertEquals("EXPIRED",orders.get(order,b.token).get("status"));assertEquals(0,inventory.get(f.variant).get("reserved"));
    }
    @Test void twoConcurrentBuyersCannotReserveTheLastUnit() throws Exception {
        var f=fixture(1);var a=basket(f,1);var b=basket(f,1);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Boolean>>();
            for(var cart:List.of(a,b)) results.add(executor.submit(()->{
                start.await();
                try { orders.checkout(cart.id,cart.token,"race-"+cart.id,cart.request);return true; }
                catch(Problem e) { assertEquals("INSUFFICIENT_STOCK",e.code);return false; }
            }));
            start.countDown();int successes=0;for(var result:results) if(result.get(20,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);
        }
        assertEquals(1,inventory.get(f.variant).get("reserved"));
    }
    @Test void insufficientLineRollsBackTheWholeOrder() {
        var f=fixture(3);var other=fixture(1);var cart=carts.create();
        UUID id=(UUID)cart.get("id");String token=(String)cart.get("accessToken");
        carts.set(id,token,f.variant,1);carts.set(id,token,other.variant,1);
        var quote=carts.quote(id,token,f.district);
        inventory.adjust(other.variant,new Api.Adjustment(1,-1,"Agotado"),"test");
        var request=new Api.Checkout("Cliente","cliente@example.com","999999999","Dirección",f.district,(String)quote.get("fingerprint"));
        assertThrows(Problem.class,()->orders.checkout(id,token,"rollback-test-1",request));
        assertEquals(0,inventory.get(f.variant).get("reserved"));assertTrue(db.rows("SELECT id FROM orders WHERE cart_id=?",id).isEmpty());
    }
    @Test @TestSecurity(user="admin-test",roles={"admin"})
    void invalidPricesAreRejectedBeforePersistence() {
        var f=fixture(1);
        var request=new Api.CreateProduct("invalid-"+UUID.randomUUID(),draft(f.category,"BAD-"+UUID.randomUUID(),"-1"));
        given().contentType("application/json").body(request).post("/api/v1/admin/products").then().statusCode(400);
    }
    @Test void unpublishedProductsAreNeverPublic() {
        String suffix=UUID.randomUUID().toString();
        UUID category=(UUID)catalog.createCategory(new Api.Category("Ropa","cat-"+suffix)).get("id");
        UUID product=(UUID)catalog.create(new Api.CreateProduct("draft-"+suffix,draft(category,suffix,"10"))).get("id");
        given().get("/api/v1/products/"+product).then().statusCode(404);
    }
    @Test void databaseRejectsImpossibleStock() {
        var f=fixture(1);
        assertThrows(Problem.class,()->QuarkusTransaction.requiringNew().run(()->db.execute("UPDATE inventory SET reserved=2 WHERE variant_id=?",f.variant)));
        assertEquals(0,inventory.get(f.variant).get("reserved"));
    }
}



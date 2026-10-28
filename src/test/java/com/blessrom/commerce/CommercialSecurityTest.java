package com.blessrom.commerce;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
@QuarkusTest
class CommercialSecurityTest {
    @Test void adminRequiresAuthentication() { given().get("/api/v1/admin/products").then().statusCode(401); }
    @Test @TestSecurity(user="customer",roles={"customer"})
    void customersCannotManageCommerce() { given().get("/api/v1/admin/products").then().statusCode(403); }
    @Test @TestSecurity(user="administrator",roles={"admin"})
    void administratorsCanManageCommerce() { given().get("/api/v1/admin/products").then().statusCode(200); }
}


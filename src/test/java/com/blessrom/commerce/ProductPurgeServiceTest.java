package com.blessrom.commerce;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class ProductPurgeServiceTest {
 static class GuardDb extends Db {
  boolean sold,stock,running;int writes;
  @Override public Map<String,Object> one(String sql,Object...args){return Map.of("version",4L,"deleting",false);}
  @Override public List<Map<String,Object>> rows(String sql,Object...args){
   if(sql.contains("FROM order_items")&&sold)return List.of(Map.of("exists",1));
   if(sql.contains("FROM inventory i")&&stock)return List.of(Map.of("exists",1));
   if(sql.contains("SELECT * FROM ai_studio_jobs")&&running)return List.of(Map.of("id",UUID.randomUUID(),"status","RUNNING"));
   return List.of();
  }
  @Override public int execute(String sql,Object...args){writes++;return 1;}
 }
 @Test void soldProductsNeverReachThePurgeQueue(){var db=new GuardDb();db.sold=true;assertBlocked(db,"PRODUCT_HAS_ORDERS");}
 @Test void physicalStockNeverReachesThePurgeQueue(){var db=new GuardDb();db.stock=true;assertBlocked(db,"PRODUCT_HAS_STOCK");}
 @Test void activeAiWorkNeverReachesThePurgeQueue(){var db=new GuardDb();db.running=true;assertBlocked(db,"PRODUCT_AI_RUNNING");}
 private void assertBlocked(GuardDb db,String code){var service=new ProductPurgeService();service.db=db;var error=assertThrows(Problem.class,()->service.enqueue(UUID.randomUUID(),null,4L,"test-purge-key","admin"));assertEquals(code,error.code);assertEquals(0,db.writes);}
}

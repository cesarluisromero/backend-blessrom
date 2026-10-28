package com.blessrom.system;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SystemResourceTest {
    @Test
    void exposesThePublicApiContractForPeru() {
        given().when().get("/api/v1/system")
                .then().statusCode(200).contentType("application/json")
                .body("brand", equalTo("Blessrom"))
                .body("service", equalTo("backend-blessrom"))
                .body("country", equalTo("PE"))
                .body("currency", equalTo("PEN"));
    }

    @Test
    void serviceIsReady() {
        given().when().get("/q/health/ready")
                .then().statusCode(200).body("status", equalTo("UP"));
    }
}

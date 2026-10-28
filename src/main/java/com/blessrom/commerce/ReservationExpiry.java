package com.blessrom.commerce;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
@ApplicationScoped public class ReservationExpiry {
    @Inject Db db;
    @Inject OrderService orders;
    @Scheduled(every="30s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void expire() {
        for(var row:db.rows("SELECT id FROM orders WHERE status='PENDING_PAYMENT' AND reservation_expires_at<=now() ORDER BY reservation_expires_at LIMIT 100")) {
            try { orders.expire(Db.id(row,"id")); }
            catch(RuntimeException e) { Logger.getLogger(ReservationExpiry.class).error("No se pudo liberar una reserva; se reintentará.",e); }
        }
    }
}


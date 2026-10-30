package com.blessrom.commerce;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.*;

@ApplicationScoped
public class NotificationDelivery {
    private static final Logger LOG=Logger.getLogger(NotificationDelivery.class);
    @Inject Db db;
    @Inject Mailer mailer;
    @ConfigProperty(name="blessrom.notifications.enabled",defaultValue="false") boolean enabled;

    @Scheduled(every="30s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void deliver() {
        if(!enabled) return;
        for(var row:db.rows("SELECT * FROM notification_outbox WHERE status='PENDING' AND next_attempt_at<=now() ORDER BY created_at LIMIT 10")) send(row);
    }
    private void send(Map<String,Object> row) {
        UUID id=Db.id(row,"id");int attempts=Db.integer(row,"attempts");
        try {
            var message=Mail.withHtml(row.get("recipient").toString(),row.get("subject").toString(),row.get("html_body").toString());
            message.setText(row.get("text_body").toString());
            mailer.send(message);
            db.execute("UPDATE notification_outbox SET status='SENT',attempts=attempts+1,sent_at=now(),last_error=NULL WHERE id=? AND status='PENDING'",id);
        } catch(RuntimeException e) {
            int next=attempts+1;
            db.execute("UPDATE notification_outbox SET status=?,attempts=?,next_attempt_at=now()+(? * interval '1 minute'),last_error=? WHERE id=? AND status='PENDING'",
                next>=5?"FAILED":"PENDING",next,Math.min(next*next,30),error(e),id);
            LOG.warnf("No se pudo enviar la notificación %s; intento %d.",id,next);
        }
    }
    static String error(Throwable error) {
        String message=error.getMessage()==null?"sin detalle":error.getMessage().replaceAll("[\\r\\n]+"," ");
        String value=error.getClass().getSimpleName()+": "+message;
        return value.substring(0,Math.min(value.length(),500));
    }
}

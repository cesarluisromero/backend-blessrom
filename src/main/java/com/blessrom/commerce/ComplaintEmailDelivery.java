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
public class ComplaintEmailDelivery {
    private static final Logger LOG=Logger.getLogger(ComplaintEmailDelivery.class);
    @Inject Db db;
    @Inject Mailer mailer;
    @ConfigProperty(name="blessrom.notifications.enabled",defaultValue="false") boolean enabled;

    @Scheduled(every="30s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void deliver() {
        if(!enabled)return;
        for(var row:db.rows("SELECT * FROM complaint_email_outbox WHERE status='PENDING' AND next_attempt_at<=now() ORDER BY created_at LIMIT 10")) {
            var id=Db.id(row,"id");int attempts=Db.integer(row,"attempts");
            try {
                mailer.send(Mail.withText(row.get("recipient").toString(),row.get("subject").toString(),row.get("body").toString()));
                db.execute("UPDATE complaint_email_outbox SET status='SENT',attempts=attempts+1,sent_at=now(),last_error=NULL WHERE id=? AND status='PENDING'",id);
            } catch(RuntimeException e) {
                int next=attempts+1;
                db.execute("UPDATE complaint_email_outbox SET status=?,attempts=?,next_attempt_at=now()+(? * interval '1 minute'),last_error=? WHERE id=? AND status='PENDING'",
                    next>=5?"FAILED":"PENDING",next,Math.min(next*next,30),NotificationDelivery.error(e),id);
                LOG.warnf("No se pudo enviar la constancia de reclamación %s; intento %d.",id,next);
            }
        }
    }
}

package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static cn.course.antifraud.Support.*;

@Service
public class QueryLimiter {
    private final Support s;
    public QueryLimiter(Support s){this.s=s;}
    // Shared by sessions and application instances. Failed queries consume quota too.
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=Problem.class)
    public void consume(String remoteAddress){
        String key=s.fingerprint("query-client",remoteAddress);var now=s.now();
        s.db.update("INSERT INTO af_query_limit(client_key,attempts,window_end) VALUES(?,1,?) ON DUPLICATE KEY UPDATE attempts=IF(window_end<=?,1,attempts+1),window_end=IF(window_end<=?,VALUES(window_end),window_end)",key,now.plusMinutes(1),now,now);
        if(s.count("SELECT attempts FROM af_query_limit WHERE client_key=?",key)>60)throw new Problem(429,"查询过于频繁，请一分钟后再试");
    }
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    public void cleanup(){s.db.update("DELETE FROM af_query_limit WHERE window_end<?",s.now().minusHours(1));}
}

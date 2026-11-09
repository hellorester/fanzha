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
        consume("query-client",remoteAddress,60,60);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=Problem.class)
    public void consume(String action,String identity,int maximum,int seconds){
        String key=s.fingerprint(action,identity);var now=s.now();
        s.db.update("INSERT INTO af_query_limit(client_key,attempts,window_end) VALUES(?,1,?) ON DUPLICATE KEY UPDATE attempts=IF(window_end<=?,1,LEAST(attempts+1,?)),window_end=IF(window_end<=?,VALUES(window_end),window_end)",key,now.plusSeconds(seconds),now,maximum+1,now);
        if(s.count("SELECT attempts FROM af_query_limit WHERE client_key=?",key)>maximum)throw new Problem(429,"操作过于频繁，请稍后重试");
    }
    public void check(String action,String identity,int maximum){
        if(s.count("SELECT COUNT(*) FROM af_query_limit WHERE client_key=? AND window_end>? AND attempts>=?",s.fingerprint(action,identity),s.now(),maximum)>0)throw new Problem(429,"登录失败次数过多，请五分钟后重试");
    }
    public void clear(String action,String identity){s.db.update("DELETE FROM af_query_limit WHERE client_key=?",s.fingerprint(action,identity));}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void cleanup(){s.db.update("DELETE FROM af_query_limit WHERE window_end<?",s.now());}
}

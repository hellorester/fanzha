package cn.course.antifraud;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import static cn.course.antifraud.Support.*;
@Component
public class Timer {
 private final Support s;private final Commands commands;private final Auth auth;
 public Timer(Support s,Commands commands,Auth auth){this.s=s;this.commands=commands;this.auth=auth;}
 @Scheduled(initialDelay=60000,fixedDelay=60000) public void scan(){
  if(!auth.isReady())return;
  long cursor=0;var now=s.now();
  // Keyset batches cover every candidate instead of starving IDs after the first 1000.
  while(true){var batch=s.rows("SELECT id FROM af_command WHERE id>? AND state IN ('已送达','受理中','生效中','已延期') ORDER BY id LIMIT 500",cursor);if(batch.isEmpty())break;
   for(var c:batch){cursor=num(c,"id");try{commands.scanOne(cursor,now,null,false);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(Timer.class).warn("scan failed for command {} {}",cursor,e.getClass().getSimpleName());}}
  }
 }
}

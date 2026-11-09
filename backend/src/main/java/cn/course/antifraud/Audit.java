package cn.course.antifraud;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.course.antifraud.Support.*;

/** Keyed, tamper-evident chain. A database-only chain is not an external immutable archive. */
@Service
public class Audit {
    private final Support s;
    public Audit(Support s){this.s=s;}
    public static String digest(Support s,Map<String,Object> row,String previous){
        List<Object> fields=new ArrayList<>();fields.add("audit-v1");fields.add(previous);
        for(String key:List.of("id","actor_id","actor_role","action","entity_type","entity_id","old_state","new_state","note","created_at"))fields.add(row.get(key)==null?null:row.get(key).toString());
        return s.fingerprint("audit-chain",s.encode(fields));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> verify(User user){
        user.administrator();var head=s.one("SELECT * FROM af_audit_head WHERE id=1");
        long cursor=0,count=0;String previous="0".repeat(64);
        while(true){
            var rows=s.rows("SELECT * FROM af_log WHERE id>? ORDER BY id LIMIT 500",cursor);if(rows.isEmpty())break;
            for(var row:rows){
                cursor=num(row,"id");count++;
                if(!previous.equals(str(row,"prev_hash"))||!digest(s,row,previous).equals(str(row,"entry_hash")))return map("ok",false,"checked",count,"firstInvalid",cursor,"message","日志内容或链路校验失败","sealedAt",head.get("sealed_at"));
                previous=str(row,"entry_hash");
            }
        }
        boolean ok=cursor==num(head,"last_id")&&count==num(head,"entry_count")&&previous.equals(str(head,"last_hash"));
        return map("ok",ok,"checked",count,"firstInvalid",ok?null:cursor,"message",ok?"日志链校验通过":"日志尾部或条数与校验头不符","sealedAt",head.get("sealed_at"));
    }
}

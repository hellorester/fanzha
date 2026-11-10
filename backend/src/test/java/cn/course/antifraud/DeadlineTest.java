package cn.course.antifraud;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static cn.course.antifraud.Support.*;

class DeadlineTest {
    private final LocalDateTime base=LocalDateTime.of(2026,11,9,12,0);
    private Support support(JdbcTemplate db,Map<String,Object> command){
        return support(db,command,base);
    }
    private Support support(JdbcTemplate db,Map<String,Object> command,LocalDateTime clock){
        return new Support(db,null,Base64.getEncoder().encodeToString(new byte[32])){
            @Override public Map<String,Object> one(String query,Object... args){return query.contains("FROM af_bank")?map("id",1,"available",1000):command;}
            @Override public Map<String,Object> lockAlert(long id){return command;}
            @Override public Map<String,Object> optional(String query,Object... args){return command;}
            @Override public void log(User u,String action,String type,long id,String oldState,String newState,String note){}
            @Override public LocalDateTime now(){return clock;}
        };
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void deliveryAndReceiptTimeoutAtTheExactSecond(int offset){
        for(String state:List.of("已送达","受理中")){
            var db=mock(JdbcTemplate.class);var cfg=mock(Clues.class);when(cfg.config()).thenReturn(Rules.defaults());
            var command=map("id",1L,"kind","止付","state",state,"created_at",base.minusHours(2),"accepted_at",state.equals("受理中")?base:null,"period_version",1);
            if(state.equals("已送达"))command.put("created_at",base);
            var now=base.plusMinutes(30).plusSeconds(offset);
            new Commands(support(db,command,now),cfg,mock(Files.class)).scanOne(1,base,null,false);
            verify(db,offset<0?never():times(1)).update("UPDATE af_command SET state='未处理',active_key=NULL WHERE id=?",1L);
        }
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void frozenFundsReleaseAtTheExactExpirySecond(int offset){
        for(String state:List.of("生效中","已延期")){
            var db=mock(JdbcTemplate.class);var command=map("id",1L,"kind","止付","bank_id",1L,"state",state,"expires_at",base,"remaining",100,"period_version",1);
            assertEquals(base,Commands.deadline(command,30));
            new Commands(support(db,command,base.plusSeconds(offset)),mock(Clues.class),mock(Files.class)).scanOne(1,base.minusMinutes(1),null,false);
            verify(db,offset<0?never():times(1)).update(eq("UPDATE af_bank SET available=? WHERE id=?"),eq(money(1100)),eq(1));
        }
    }
}

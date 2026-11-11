package cn.course.antifraud;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static cn.course.antifraud.Support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommandVisibilityTest {
    private final Support s=mock(Support.class);
    private final Files files=mock(Files.class);
    private final Relationships relationships=mock(Relationships.class);
    private final Commands commands=new Commands(s,mock(Clues.class),files,relationships);

    private Map<String,Object> child(String kind){
        return map("id",24L,"code","JC24","kind",kind,"state","拒绝执行","requested",100,"actual",0,
            "bank_id",1L,"org_id",1L,"victim_id",2L);
    }
    private Map<String,Object> application(User user,String kind){
        var app=map("id",7L,"kind",kind.equals("解除")?"申诉":"返还","owner_id",user.id(),"original_id",23L,"state","已生成解除指令");
        if(user.role().equals("PUBLIC"))when(s.one(contains("FROM af_application"),eq(7L),eq(user.id()))).thenReturn(app);
        else when(s.one(contains("FROM af_application"),eq(7L))).thenReturn(app);
        when(s.one(contains("FROM af_command"),eq(23L))).thenReturn(map("kind","止付","state","已解除","remaining",0,"reserved",0));
        when(s.rows(contains("FROM af_command"),eq(7L))).thenReturn(List.of(child(kind)));
        when(relationships.holder(anyMap())).thenReturn(3L);
        when(s.now()).thenReturn(LocalDateTime.of(2026,11,11,12,0));
        return commands.application(user,7);
    }

    @ParameterizedTest
    @CsvSource({"解除,2,true,false","解除,3,true,true","解除,3,false,false","返还,2,true,true","返还,3,true,false","返还,2,false,false"})
    void applicationLinksUseTheSameCurrentIdentityAsCommandDetails(String kind,long person,boolean verified,boolean allowed){
        var user=new User(5,"public","居民","PUBLIC",0,0,person,verified,false);
        var result=application(user,kind);
        var children=(List<Map<String,Object>>)result.get("commands");
        assertEquals(1,children.size());var row=children.getFirst();
        assertEquals(allowed,row.get("detail_available"));
        assertEquals("拒绝执行",row.get("state"));assertEquals(0,row.get("actual"));
        assertEquals(Set.of("id","code","kind","state","requested","actual","detail_available"),row.keySet());
        if(allowed)assertDoesNotThrow(()->commands.access(user,child(kind)));
        else assertEquals(404,assertThrows(Problem.class,()->commands.access(user,child(kind))).status);
    }

    @Test void centerCanInspectHistoricalApplicationCommands(){
        var center=new User(1,"center","中心","CENTER",0,0,0,false,true);
        var result=application(center,"解除");
        assertEquals(true,((List<Map<String,Object>>)result.get("commands")).getFirst().get("detail_available"));
        assertDoesNotThrow(()->commands.access(center,child("解除")));
    }

    @Test void unrelatedAccountStillCannotReadAnApplicationOrItsSummary(){
        var other=new User(6,"other","其他居民","PUBLIC",0,0,3,true,false);
        when(s.one(contains("FROM af_application"),eq(7L),eq(6L))).thenThrow(new Problem(404,"记录不存在或无权访问"));
        assertEquals(404,assertThrows(Problem.class,()->commands.application(other,7)).status);
        verifyNoInteractions(files,relationships);
    }

    @Test void institutionAndRoleRestrictionsRemainInForce(){
        assertDoesNotThrow(()->commands.access(new User(1,"pay","机构","PAYMENT",1,0,0,false,false),child("解除")));
        assertEquals(404,assertThrows(Problem.class,()->commands.access(new User(2,"pay2","其他机构","PAYMENT",2,0,0,false,false),child("解除"))).status);
        assertEquals(403,assertThrows(Problem.class,()->commands.access(new User(3,"grid","网格","GRID",0,1,0,false,false),child("解除"))).status);
    }
}

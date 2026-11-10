package cn.course.antifraud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static cn.course.antifraud.Support.*;
import static org.junit.jupiter.api.Assertions.*;

class RefundProgressTest {
    final LocalDateTime now=LocalDateTime.of(2026,11,10,12,0);
    Map<String,Object> original(){return map("kind","止付","state","生效中","remaining",1000,"reserved",600,"expires_at",now.plusHours(1));}
    Map<String,Object> app(){return map("state","核定通过","approved",600);}
    Map<String,Object> child(String state,int actual){return map("kind","返还","state",state,"actual",actual);}

    @Test void approvalIsNotCredit(){
        var p=RefundProgress.describe(app(),original(),List.of(child("受理中",0)),now);
        assertEquals("等待执行",p.get("state"));assertEquals(0,amount(p,"credited").signum());
    }
    @ParameterizedTest @ValueSource(strings={"未处理","已拒绝","拒绝执行"})
    void onlyLatestAttemptDeterminesFailure(String failed){
        var history=List.of(child(failed,0));
        assertEquals("执行失败",RefundProgress.describe(app(),original(),history,now).get("state"));
        assertEquals("等待执行",RefundProgress.describe(app(),original(),List.of(history.getFirst(),child("已送达",0)),now).get("state"));
        var p=RefundProgress.describe(app(),original(),List.of(history.getFirst(),child("已执行",600)),now);
        assertEquals("已成功入账",p.get("state"));assertEquals(money(600),p.get("credited"));
    }
    @Test void expirationAtExactDeadlinePreventsRetryAdvice(){
        var origin=original();origin.put("expires_at",now);
        var p=RefundProgress.describe(app(),origin,List.of(child("未处理",0)),now);
        assertEquals("办理已结束",p.get("state"));assertFalse(str(p,"note").contains("可从"));
    }
    @Test void partialCreditSurvivesFreezeClosure(){
        var origin=original();origin.put("state","已解除");origin.put("remaining",0);
        var p=RefundProgress.describe(app(),origin,List.of(child("已执行",400)),now);
        assertEquals("部分入账（已结束）",p.get("state"));assertEquals(money(400),p.get("credited"));
        assertTrue(str(p,"note").contains("不能基于该止付继续办理"));
    }
    @Test void partialCreditWhileFreezeIsLiveDoesNotPromiseAutomaticPayment(){
        var p=RefundProgress.describe(app(),original(),List.of(child("已执行",400)),now);
        assertEquals("部分入账",p.get("state"));assertTrue(str(p,"note").contains("不会自动再次划款"));
    }
    @Test void partialCreditAtUnscannedDeadlineIsAlreadyFinished(){
        var origin=original();origin.put("expires_at",now);
        var p=RefundProgress.describe(app(),origin,List.of(child("已执行",400)),now);
        assertEquals("部分入账（已结束）",p.get("state"));assertEquals(money(400),p.get("credited"));
    }
    @Test void fullCreditRemainsSuccessfulAfterClosure(){
        var origin=original();origin.put("state","已解除");origin.put("remaining",0);
        assertEquals("已成功入账",RefundProgress.describe(app(),origin,List.of(child("已执行",600)),now).get("state"));
    }
    @Test void invalidApplicationCannotAdvertiseRetry(){
        var a=app();a.put("state","已失效");
        assertEquals("申请已失效",RefundProgress.describe(a,original(),List.of(child("已拒绝",0)),now).get("state"));
    }
}

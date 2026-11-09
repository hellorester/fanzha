package cn.course.antifraud;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.course.antifraud.Support.*;
import java.util.*;
class RulesTest {
 @Test void canonicalUrlVariants(){
  assertEquals(Rules.normalize("网址","https://example.com/login"),Rules.normalize("网址","https://EXAMPLE.com.:443/login"));
  assertEquals(Rules.normalize("网址","http://example.com/login"),Rules.normalize("网址","http://example.com:80/login"));
  assertEquals(Rules.normalize("网址","https://例子.测试/login"),Rules.normalize("网址","https://xn--fsqu00a.xn--0zwm56d:443/login"));
  assertNotEquals(Rules.normalize("网址","https://example.com/login"),Rules.normalize("网址","https://example.com:8443/login"));
  assertEquals("[::1]/login",Rules.normalize("网址","https://[::1]:443/login"));
  assertEquals("example.com/login",Rules.normalize("网址","https://example.com/a/../login"));
  for(String value:List.of("https://user@example.com/","https://example.com:/","https://example.com:65536/","https://example.com:abc/","https://bad_host/"))assertThrows(Problem.class,()->Rules.normalize("网址",value),value);
 }
 @Test void overflowingAndFractionalWeightsAreRejectedAndFallBack(){
  for(Object invalid:List.of(4294967321L,"4294967321","25.5","1e100")){
   var c=Rules.defaults();c.put("weights",List.of(invalid,15,15,30,15));assertThrows(Problem.class,()->Rules.validateConfig(c));
   assertTrue(yes(Rules.score(map(),true,c),"fallback"));assertEquals(30,num(Rules.score(map(),true,c),"score"));
  }
  var overflow=Rules.defaults();overflow.put("weights",List.of(2147483647,2147483647,100,1,1));assertThrows(Problem.class,()->Rules.validateConfig(overflow));
  var fractional=Rules.defaults();fractional.put("timeoutMinutes","1.5");assertThrows(Problem.class,()->Rules.validateConfig(fractional));
 }
 @Test void sensitiveFingerprintsDependOnKeyAndKind(){
  byte[] key=new byte[32];var first=new Support(null,null,Base64.getEncoder().encodeToString(key));key[0]=1;var second=new Support(null,null,Base64.getEncoder().encodeToString(key));
  String value="13800000009",fingerprint=first.fingerprint("号码",value);
  assertEquals(64,fingerprint.length());assertEquals(fingerprint,first.fingerprint("号码",value));
  assertNotEquals(hash(value),fingerprint);assertNotEquals(fingerprint,second.fingerprint("号码",value));assertNotEquals(fingerprint,first.fingerprint("账户",value));
 }
 @Test void classificationThresholds(){int[] scores={39,40,69,70};String[] levels={"低","中","中","高"};for(int i=0;i<scores.length;i++){var c=Rules.defaults();c.put("weights",List.of(1,1,1,scores[i],97-scores[i]));var r=Rules.score(map(),true,c);assertEquals(scores[i],num(r,"score"));assertEquals(levels[i],str(r,"level"));}}
 @Test void canonicalExamples(){assertEquals(79,num(Rules.score(map("amount",6000,"transactionTime","23:30","remote",true),true,Rules.defaults()),"score"));assertEquals(10,num(Rules.score(map("amount",999,"transactionTime","12:00"),false,Rules.defaults()),"score"));}
 @Test void moneyBoundaries(){String[] amounts={"999.99","1000","4999.99","5000","19999.99","20000"};int[] expected={20,50,50,75,75,100};for(int i=0;i<amounts.length;i++){var result=Rules.score(map("amount",amounts[i]),false,Rules.defaults());var ds=(List<?>)result.get("details");assertEquals(expected[i],((Map<?,?>)ds.getFirst()).get("factor"));}}
 @Test void timeBoundaries(){String[] times={"22:59:59","23:00","04:59:59","05:00"};int[] expected={30,100,100,30};for(int i=0;i<times.length;i++){var result=Rules.score(map("transactionTime",times[i]),false,Rules.defaults());assertEquals(expected[i],((Map<?,?>)((List<?>)result.get("details")).get(1)).get("factor"));}}
 @Test void missingIsZero(){assertEquals(0,num(Rules.score(map(),false,Rules.defaults()),"score"));}
 @Test void configFallback(){var c=Rules.defaults();c.put("weights",List.of(1,2));assertTrue(yes(Rules.score(map(),false,c),"fallback"));assertThrows(Problem.class,()->Rules.validateConfig(c));}
 @Test void normalization(){assertEquals("13800000009",Rules.normalize("号码","0086 138-0000-0009"));assertEquals("00123456",Rules.normalize("账户","00 1234-56"));assertEquals("example.com/Case",Rules.normalize("网址","HTTPS://EXAMPLE.COM/Case/?x=1"));assertThrows(Problem.class,()->Rules.normalize("网址","javascript:alert(1)"));}
 @Test void decimalValidation(){assertThrows(Problem.class,()->money("0.001"));assertThrows(Problem.class,()->Rules.score(map("amount",-1),false,Rules.defaults()));}
 @Test void passwordSaltAndVerification(){String a=password("Demo2026!"),b=password("Demo2026!");assertNotEquals(a,b);assertTrue(checkPassword("Demo2026!",a));assertFalse(checkPassword("wrong",a));}
}

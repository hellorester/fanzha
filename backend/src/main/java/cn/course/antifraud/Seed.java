package cn.course.antifraud;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.course.antifraud.Support.*;

@Component
@org.springframework.core.annotation.Order(1)
public class Seed implements CommandLineRunner {
    private final Support s;private final boolean enabled;
    public Seed(Support s,@Value("${app.seed}") boolean enabled){this.s=s;this.enabled=enabled;}
    @Transactional public void run(String...args){
        if(!enabled||s.count("SELECT COUNT(*) FROM af_user")>0)return;
        s.db.update("INSERT INTO af_org VALUES(1,'钱塘支付机构（模拟）'),(2,'城西支付机构（模拟）')");
        s.db.update("INSERT INTO af_community VALUES(1,'青禾社区'),(2,'云栖社区')");
        for(int i=1;i<=3;i++)s.db.update("INSERT INTO af_victim(id,name_enc,phone_enc,identity_enc,community_id) VALUES(?,?,?,?,?)",i,s.encrypt("演示居民"+i),s.encrypt("1380000000"+i),s.encrypt("DEMO-ID-"+i),i==2?2:1);
        String password=password("Demo2026!");
        Object[][] users={{1,"center","中心管理员","CENTER",null,null,null,false,true},{2,"public1","演示居民一","PUBLIC",null,null,1,true,false},{3,"pay1","钱塘机构经办员","PAYMENT",1,null,null,false,false},{4,"grid1","青禾网格员林","GRID",null,1,null,false,false},{5,"public2","演示居民二","PUBLIC",null,null,2,true,false},{6,"pay2","城西机构经办员","PAYMENT",2,null,null,false,false},{7,"grid2","青禾网格员周","GRID",null,1,null,false,false},{8,"grid3","云栖网格员陈","GRID",null,2,null,false,false},{9,"reviewer","中心研判员","CENTER",null,null,null,false,false},{10,"public3","账户持有人三","PUBLIC",null,null,3,true,false}};
        for(Object[] u:users)s.db.update("INSERT INTO af_user(id,username,password_hash,display_name,role,org_id,community_id,victim_id,verified,admin) VALUES(?,?,?,?,?,?,?,?,?,?)",u[0],u[1],password,u[2],u[3],u[4],u[5],u[6],u[7],u[8]);
        for(int i=1;i<=4;i++){String number="622200000000000"+i;s.db.update("INSERT INTO af_bank(id,org_id,number_hash,number_enc,holder_enc,available,owner_person_id) VALUES(?,?,?,?,?,?,?)",i,i==2?2:1,s.fingerprint("账户",number),s.encrypt(number),s.encrypt("演示居民"+(i<=2?3:i==3?1:2)),i==4?600:60000,i<=2?3:i==3?1:2);}
        s.db.update("INSERT INTO af_bank_origin(bank_id,opening,source,created_at) SELECT id,available,'初始配置',? FROM af_bank",s.now());
        s.db.update("INSERT INTO af_config(data,author_id) VALUES(?,1)",s.encode(Rules.defaults()));
        for(String[] row:new String[][]{{"号码","13800000009","课程模拟风险号码"},{"账户","6222000000000001","课程模拟风险账户"},{"网址","https://demo-risk.example/loan","课程模拟风险网址"}})s.db.update("INSERT INTO af_blacklist(kind,value_hash,value_enc,source) VALUES(?,?,?,?)",row[0],s.fingerprint(row[0],Rules.normalize(row[0],row[1])),s.encrypt(row[1]),row[2]);
        String[][] articles={{"转账前，多核实一步","防范提示","遇到催促转账、要求共享屏幕或陌生链接，请先暂停操作。","陌生来电要求转账时，先结束通话，再通过自己查到的官方渠道核实。不要提供验证码或开启屏幕共享。"},{"识别冒充客服的退款陷阱","典型案例","“退款”却要求先转账，是需要警惕的信号。","居民接到自称客服的电话，对方以退款为由要求进入陌生网站并提供验证码。居民停止操作、保留聊天和转账材料后提交线索。核实身份时应使用原购物平台的官方客服。"},{"举报材料怎样准备","举报指南","保存原始号码、网址、交易记录和沟通内容，让线索更清晰。","按时间顺序描述经过，填写可疑号码、账户或网址，上传必要截图。涉及敏感内容时避免公开传播。即使资料不完整，也可以先提交线索；匿名受理后请妥善保存查询凭证。"}};
        for(String[] a:articles)s.db.update("INSERT INTO af_article(title,category,summary,body,state,updated_at) VALUES(?,?,?,?,'已发布',?)",a[0],a[1],a[2],a[3],s.now());
        s.db.update("INSERT INTO af_campaign(title,community_id,description,due_at,created_at) VALUES('守护长辈的钱袋子',1,'面向社区老年居民开展冒充客服与投资诈骗防范宣传。',?,?)",s.now().plusDays(7),s.now());
        s.log(null,"初始化","system",0,null,null,"创建虚拟演示资料与默认配置");
    }
}

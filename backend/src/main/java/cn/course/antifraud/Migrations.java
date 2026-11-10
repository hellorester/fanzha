package cn.course.antifraud;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import static cn.course.antifraud.Support.*;

/** Versioned, restart-safe upgrades for existing installations; never recreates business tables. */
@Component
@Order(0)
public class Migrations implements CommandLineRunner {
    private final Support s;
    private final TransactionTemplate tx;
    public Migrations(Support s,PlatformTransactionManager manager){this.s=s;tx=new TransactionTemplate(manager);}
    public void run(String... args)throws Exception{
        // A connection-scoped lock survives MySQL DDL's implicit commits.
        try(Connection lock=s.db.getDataSource().getConnection()){
            String name="antifraud-upgrade-"+hash(lock.getCatalog()).substring(0,40);
            try(var statement=lock.prepareStatement("SELECT GET_LOCK(?,30)")){
                statement.setString(1,name);try(var rows=statement.executeQuery()){if(!rows.next()||rows.getInt(1)!=1)throw new IllegalStateException("数据库升级锁超时");}
            }
            try{
                if(!applied(1)){
                    if(s.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_log' AND COLUMN_NAME='actor_role'")==0)s.db.execute("ALTER TABLE af_log ADD COLUMN actor_role VARCHAR(20) AFTER actor_id");
                    // The original unnamed UNIQUE(kind,value_hash) was named "kind" by MySQL.
                    // Never remove unrelated or same-named-but-different team indexes.
                    var legacy=s.rows("SELECT COLUMN_NAME,NON_UNIQUE,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='kind' ORDER BY SEQ_IN_INDEX");
                    if(!legacy.isEmpty()){
                        require(legacy.size()==2&&str(legacy.get(0),"COLUMN_NAME").equals("kind")&&str(legacy.get(1),"COLUMN_NAME").equals("value_hash")&&legacy.stream().allMatch(i->num(i,"NON_UNIQUE")==0&&i.get("SUB_PART")==null),"旧黑名单索引结构与预期不符，升级停止，请先检查索引");
                        s.db.execute("ALTER TABLE af_blacklist DROP INDEX `kind`");
                    }
                    index("af_blacklist","ix_blacklist_value","kind,value_hash");
                    index("af_attachment","ix_attachment_draft_owner","owner_key,entity_id,created_at");
                    index("af_attachment","ix_attachment_draft_user","user_id,entity_id,created_at");
                    mark(1);
                }
                if(!applied(2))tx.executeWithoutResult(status->{
                    reindex("af_bank","number_enc","number_hash","账户");
                    reindex("af_blacklist","value_enc","value_hash",null);
                    reindex("af_report","value_enc","value_hash",null);
                    mark(2);
                });
                if(!applied(3))tx.executeWithoutResult(status->{
                    for(var bank:s.rows("SELECT id,available FROM af_bank ORDER BY id")){
                        boolean explicitBank=s.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_ledger' AND COLUMN_NAME='bank_id'")>0;
                        var first=s.optional("SELECT l.* FROM af_ledger l JOIN af_command c ON c.id=l.command_id WHERE "+(explicitBank?"COALESCE(l.bank_id,c.bank_id)":"c.bank_id")+"=? ORDER BY l.id LIMIT 1",bank.get("id"));
                        var opening=first==null?amount(bank,"available"):amount(first,"available_after").subtract(Reconciliation.delta(first));
                        s.db.update("INSERT INTO af_bank_origin(bank_id,opening,source,created_at) VALUES(?,?,?,?)",bank.get("id"),opening,first==null?"升级余额基线":"历史账本推算",s.now());
                    }
                    mark(3);
                });
                if(!applied(4))tx.executeWithoutResult(status->{
                    // URL keys now normalize unreserved escapes and IPv6 literals as well.
                    reindex("af_blacklist","value_enc","value_hash",null);
                    reindex("af_report","value_enc","value_hash",null);
                    mark(4);
                });
                if(!applied(5)){
                    for(String column:new String[]{"prev_hash","entry_hash"})if(s.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_log' AND COLUMN_NAME=?",column)==0)s.db.execute("ALTER TABLE af_log ADD COLUMN "+column+" CHAR(64)");
                    tx.executeWithoutResult(status->{
                        long cursor=0,count=0;String previous="0".repeat(64);
                        while(true){
                            var rows=s.rows("SELECT * FROM af_log WHERE id>? ORDER BY id LIMIT 500",cursor);if(rows.isEmpty())break;
                            for(var row:rows){cursor=num(row,"id");count++;String digest=Audit.digest(s,row,previous);s.db.update("UPDATE af_log SET prev_hash=?,entry_hash=? WHERE id=?",previous,digest,cursor);previous=digest;}
                        }
                        s.db.update("INSERT INTO af_audit_head(id,last_id,last_hash,entry_count,sealed_at) VALUES(1,?,?,?,?)",cursor,previous,count,s.now());
                        mark(5);
                    });
                }
                if(!applied(6)){
                    column("af_report","request_owner_key","CHAR(64)");column("af_report","query_token_enc","TEXT");
                    column("af_alert","score_config_id","BIGINT");column("af_alert","score_blacklist_version","BIGINT");
                    column("af_command","retry_of","BIGINT");index("af_command","ix_command_retry","retry_of");
                    mark(6);
                }
                if(!applied(7)){
                    s.db.execute("ALTER TABLE af_log MODIFY COLUMN note TEXT");
                    column("af_correction","base_receipt_id","BIGINT");
                    mark(7);
                }
                if(!applied(8))tx.executeWithoutResult(status->{
                    // Preserve historical decisions; only unfinished work on an ended freeze expires.
                    for(var a:s.rows("SELECT a.id,a.state FROM af_application a JOIN af_command c ON c.id=a.original_id WHERE a.state IN ('待核定','待受理','审核中','驳回待补') AND c.state IN ('已解除','拒绝执行','已作废') ORDER BY a.id")){
                        s.db.update("UPDATE af_application SET state='已失效',updated_at=? WHERE id=?",s.now(),a.get("id"));
                        s.log(null,"申请失效","application",num(a,"id"),str(a,"state"),"已失效","升级核对：原止付已结束，保留原申请及审核记录");
                    }
                    for(var x:s.rows("SELECT x.id FROM af_correction x JOIN af_command c ON c.id=x.command_id WHERE x.state='待审核' AND c.state IN ('已解除','已作废') ORDER BY x.id")){
                        s.db.update("UPDATE af_correction SET state='已失效',review_note='原止付已结束，无法继续更正' WHERE id=?",x.get("id"));
                        s.log(null,"更正失效","correction",num(x,"id"),"待审核","已失效","升级核对：原止付已结束");
                    }
                    mark(8);
                });
                if(!applied(9)){
                    column("af_bank","owner_person_id","BIGINT");
                    column("af_application","applicant_person_id","BIGINT");
                    column("af_application","payee_bank_id","BIGINT");
                    column("af_ledger","bank_id","BIGINT");
                    column("af_alert","dispatch_mode","VARCHAR(30)");
                    foreignKey("af_bank","fk_bank_person","owner_person_id","af_victim");
                    foreignKey("af_application","fk_application_person","applicant_person_id","af_victim");
                    foreignKey("af_application","fk_application_payee","payee_bank_id","af_bank");
                    foreignKey("af_ledger","fk_ledger_bank","bank_id","af_bank");
                    index("af_bank","ix_bank_owner","owner_person_id");
                    index("af_ledger","ix_ledger_bank","bank_id,id");
                    tx.executeWithoutResult(status->{
                        s.db.update("UPDATE af_ledger l JOIN af_command c ON c.id=l.command_id SET l.bank_id=c.bank_id WHERE l.bank_id IS NULL");
                        for(var c:s.rows("SELECT c.* FROM af_command c WHERE c.kind='止付' AND c.state IN ('待下发','已送达','受理中') AND NOT EXISTS(SELECT 1 FROM af_alert_bank x WHERE x.alert_id=c.alert_id AND x.bank_id=c.bank_id) ORDER BY c.id")){
                            s.db.update("UPDATE af_command SET state='未处理',active_key=NULL WHERE id=?",c.get("id"));
                            s.db.update("INSERT IGNORE INTO af_notice(command_id,period_version,node,message,created_at) VALUES(?,?,'账户待核验','旧指令缺少线索账户关联依据，请核验、复核后从原指令重发',?)",c.get("id"),c.get("period_version"),s.now());
                            s.log(null,"账户关系升级","command",num(c,"id"),str(c,"state"),"未处理","未执行的旧止付缺少目标关联依据，转中心核验后复核重发");
                        }
                        // Older versions did not record verified account owners or payees. Never infer them from a reporter.
                        for(var a:s.rows("SELECT a.* FROM af_application a WHERE (a.applicant_person_id IS NULL OR (a.kind='返还' AND a.payee_bank_id IS NULL)) AND a.state IN ('待核定','待受理','审核中','驳回待补','核定通过','已生成解除指令') ORDER BY a.id")){
                            var pending=s.rows("SELECT * FROM af_command WHERE application_id=? AND state IN ('待下发','已送达','受理中') ORDER BY id",a.get("id"));
                            boolean decided=str(a,"state").equals("核定通过")||str(a,"state").equals("已生成解除指令");
                            if(decided&&pending.isEmpty()&&(s.count("SELECT COUNT(*) FROM af_command WHERE application_id=? AND state IN ('已执行','已解除')",a.get("id"))>0||s.count("SELECT COUNT(*) FROM af_command WHERE id=? AND state IN ('已解除','拒绝执行','已作废')",a.get("original_id"))>0))continue; // Preserve completed historical decisions and balances.
                            for(var c:pending){
                                if(str(c,"kind").equals("返还"))s.db.update("UPDATE af_command SET reserved=reserved-? WHERE id=?",c.get("requested"),c.get("original_id"));
                                s.db.update("UPDATE af_command SET state='已取消',active_key=NULL WHERE id=?",c.get("id"));
                                s.log(null,"账户关系升级","command",num(c,"id"),str(c,"state"),"已取消","旧指令缺少已核验申请人或收款账户；保留历史，请核验后重新申请");
                            }
                            s.db.update("UPDATE af_application SET state='已失效',updated_at=? WHERE id=?",s.now(),a.get("id"));
                            s.log(null,"账户关系升级","application",num(a,"id"),str(a,"state"),"已失效","旧申请未保存户主或本人收款账户核验依据，请核验后重新申请；原审核意见保留");
                        }
                        mark(9);
                    });
                }
                if(!applied(10))tx.executeWithoutResult(status->{
                    // Earlier versions left exhausted freezes and their outstanding work open.
                    // This changes workflow status only; completed decisions, balances and ledger stay intact.
                    for(var c:s.rows("SELECT * FROM af_command WHERE kind='止付' AND state IN ('生效中','已延期') AND remaining=0 AND actual>0 AND returned=actual ORDER BY id FOR UPDATE")){
                        Commands.finishFreeze(s,c,null,"升级核对：冻结资金已全部返还");
                        s.log(null,"全额返还结束止付","command",num(c,"id"),str(c,"state"),"已解除","升级核对：结束待办，保留已完成的返还和审核记录，不改动资金账本");
                    }
                    mark(10);
                });
                if(!applied(11))tx.executeWithoutResult(status->{
                    // A completed freeze may still have an approved refund that never credited.
                    // Reconcile workflow status only, without rewriting approvals or money history.
                    for(var c:s.rows("SELECT c.id FROM af_command c WHERE c.kind='止付' AND c.state IN ('已解除','已作废','拒绝执行') AND EXISTS (SELECT 1 FROM af_application a WHERE a.original_id=c.id AND a.kind='返还' AND a.state='核定通过' AND NOT EXISTS (SELECT 1 FROM af_command x WHERE x.application_id=a.id AND x.kind='返还' AND x.actual>0)) ORDER BY c.id FOR UPDATE")){
                        Commands.endApplications(s,num(c,"id"),null,"升级核对：原止付已结束，未入账的返还申请不再具备执行条件");
                    }
                    mark(11);
                });
                if(!applied(12)){
                    column("af_article","cover_id","VARCHAR(40)");
                    foreignKey("af_article","fk_article_cover","cover_id","af_attachment");
                    mark(12);
                }
            }finally{
                try(var statement=lock.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
            }
        }
    }
    private void foreignKey(String table,String name,String column,String parent){
        if(s.count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?",table,name)==0)s.db.execute("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" FOREIGN KEY ("+column+") REFERENCES "+parent+"(id)");
    }
    private void column(String table,String name,String type){
        if(s.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",table,name)==0)s.db.execute("ALTER TABLE "+table+" ADD COLUMN "+name+" "+type);
    }
    private boolean applied(int version){return s.count("SELECT COUNT(*) FROM af_migration WHERE version=?",version)>0;}
    private void mark(int version){s.db.update("INSERT INTO af_migration(version,applied_at) VALUES(?,?)",version,s.now());}
    private void index(String table,String name,String columns){
        var current=s.rows("SELECT COLUMN_NAME,NON_UNIQUE,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX",table,name);
        if(current.isEmpty())s.db.execute("CREATE INDEX "+name+" ON "+table+"("+columns+")");
        else require(String.join(",",current.stream().map(i->str(i,"COLUMN_NAME")).toList()).equals(columns)&&current.stream().allMatch(i->num(i,"NON_UNIQUE")==1&&i.get("SUB_PART")==null),"索引 "+name+" 与预期结构不符，升级已停止");
    }
    private void reindex(String table,String encrypted,String hashColumn,String fixedKind){
        long cursor=0;
        while(true){
            var rows=s.rows("SELECT id,"+encrypted+(fixedKind==null?",kind":"")+" FROM "+table+" WHERE id>? ORDER BY id LIMIT 500",cursor);
            if(rows.isEmpty())return;
            for(var row:rows){cursor=num(row,"id");String kind=fixedKind==null?str(row,"kind"):fixedKind;
                s.db.update("UPDATE "+table+" SET "+hashColumn+"=? WHERE id=?",s.fingerprint(kind,Rules.normalize(kind,s.decrypt(row.get(encrypted)))),cursor);
            }
        }
    }
}

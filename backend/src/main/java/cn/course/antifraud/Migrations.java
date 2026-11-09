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
                        var first=s.optional("SELECT l.* FROM af_ledger l JOIN af_command c ON c.id=l.command_id WHERE c.bank_id=? ORDER BY l.id LIMIT 1",bank.get("id"));
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
            }finally{
                try(var statement=lock.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
            }
        }
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

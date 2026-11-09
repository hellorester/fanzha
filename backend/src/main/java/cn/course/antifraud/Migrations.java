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
                    // Equivalent URLs may already have separate source records. Preserve every row.
                    for(var index:s.rows("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'")){
                        String indexName=str(index,"INDEX_NAME");require(indexName.matches("[A-Za-z0-9_]+"),"数据库索引名称无效");s.db.execute("ALTER TABLE af_blacklist DROP INDEX `"+indexName+"`");
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
            }finally{
                try(var statement=lock.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
            }
        }
    }
    private boolean applied(int version){return s.count("SELECT COUNT(*) FROM af_migration WHERE version=?",version)>0;}
    private void mark(int version){s.db.update("INSERT INTO af_migration(version,applied_at) VALUES(?,?)",version,s.now());}
    private void index(String table,String name,String columns){
        if(s.count("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?",table,name)==0)s.db.execute("CREATE INDEX "+name+" ON "+table+"("+columns+")");
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

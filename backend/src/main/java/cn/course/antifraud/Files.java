package cn.course.antifraud;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static cn.course.antifraud.Support.*;

@Service
public class Files {
    private final Support s;private final Path root;private final TransactionTemplate tx;
    private static final int MAX_DRAFT_FILES=20;
    private static final long MAX_DRAFT_BYTES=50L*1024*1024;
    public Files(Support s,@Value("${app.storage}") String directory,PlatformTransactionManager manager)throws IOException{this.s=s;tx=new TransactionTemplate(manager);root=Path.of(directory).toAbsolutePath().normalize();java.nio.file.Files.createDirectories(root);}
    public Path path(String id){require(id.matches("[a-f0-9-]{36}"),"附件编号无效");Path p=root.resolve(id).normalize();require(p.startsWith(root),"附件路径无效");return p;}
    @Transactional public Map<String,Object> upload(MultipartFile file,User u,String owner)throws IOException{
        // The database lock serializes concurrent uploads across tabs and application instances.
        String guard=hash(u==null?"session:"+owner:"user:"+u.id());
        s.db.update("INSERT INTO af_upload_guard(owner_key,touched_at) VALUES(?,?) ON DUPLICATE KEY UPDATE touched_at=VALUES(touched_at)",guard,s.now());
        String scope=u==null?"owner_key=?":"user_id=?";Object identity=u==null?hash(owner):u.id();
        var quota=s.one("SELECT COUNT(*) files,COALESCE(SUM(bytes),0) bytes FROM af_attachment WHERE entity_id IS NULL AND "+scope,identity);
        if(num(quota,"files")>=MAX_DRAFT_FILES||num(quota,"bytes")+file.getSize()>MAX_DRAFT_BYTES)throw new Problem(429,"未提交材料最多20份、合计50MB，请先提交或移除已有材料");
        require(!file.isEmpty()&&file.getSize()<=10*1024*1024,"附件不能为空或超过10MB");String name=Path.of(Objects.toString(file.getOriginalFilename(),"附件").replace('\\','/')).getFileName().toString();require(name.length()<=180,"文件名过长");String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);byte[] data=file.getBytes();String mime;
        switch(ext){
            case "png","jpg","jpeg" -> {require(javax.imageio.ImageIO.read(new ByteArrayInputStream(data))!=null,"图片内容与格式不匹配");mime=ext.equals("png")?"image/png":"image/jpeg";}
            case "pdf" -> {require(data.length>5&&new String(data,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"),"PDF文件格式无效");mime="application/pdf";}
            case "docx" -> {boolean valid=false;try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))){java.util.zip.ZipEntry e;int entries=0;while((e=zip.getNextEntry())!=null&&entries++<1000){if(e.getName().equals("word/document.xml")){valid=true;break;}}}require(valid,"Word文件格式无效");mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
            case "txt" -> {require(new String(data,java.nio.charset.StandardCharsets.UTF_8).indexOf('\0')<0,"文本格式无效");mime="text/plain";}
            default -> throw new Problem(400,"支持PNG、JPG、PDF、DOCX、TXT文件");
        }
        String id=UUID.randomUUID().toString();Path target=path(id);java.nio.file.Files.write(target,data,StandardOpenOption.CREATE_NEW);
        try{s.db.update("INSERT INTO af_attachment(id,owner_key,user_id,filename,mime,bytes,created_at) VALUES(?,?,?,?,?,?,?)",id,hash(owner),u==null?null:u.id(),name,mime,data.length,s.now());}catch(RuntimeException e){java.nio.file.Files.deleteIfExists(target);throw e;}
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)erase(id);}
        });
        return map("id",id,"filename",name,"bytes",data.length);
    }
    @Transactional public void deleteDraft(String id,User u,String owner){
        path(id);var file=s.optional("SELECT * FROM af_attachment WHERE id=? FOR UPDATE",id);if(file==null)return;
        if(!str(file,"owner_key").equals(hash(owner))&&(u==null||num(file,"user_id")!=u.id()))throw new Problem(404,"附件不存在或无权访问");
        conflict(file.get("entity_id")==null,"已提交的业务材料不能移除");removeDraft(id);
    }
    private void removeDraft(String id){
        s.db.update("DELETE FROM af_attachment WHERE id=? AND entity_id IS NULL",id);
        // Keep bytes until commit: a failed transaction must not destroy attached evidence.
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){erase(id);}});
        else erase(id);
    }
    private void erase(String id){try{java.nio.file.Files.deleteIfExists(path(id));}catch(IOException e){org.slf4j.LoggerFactory.getLogger(Files.class).warn("Attachment cleanup deferred for {}",id);}}
    @Scheduled(initialDelayString="${app.attachments.cleanup-initial-delay:60000}",fixedDelayString="${app.attachments.cleanup-delay:3600000}")
    public void cleanup(){
        var cutoff=s.now().minusHours(24);
        for(var candidate:s.rows("SELECT id FROM af_attachment WHERE entity_id IS NULL AND created_at<? ORDER BY created_at LIMIT 500",cutoff)){
            String id=str(candidate,"id");tx.executeWithoutResult(status->{
                var f=s.optional("SELECT * FROM af_attachment WHERE id=? FOR UPDATE",id);
                if(f!=null&&f.get("entity_id")==null&&time(f.get("created_at")).isBefore(cutoff))removeDraft(id);
            });
        }
        // Recover filesystem-only leftovers after a crash between writing bytes and committing SQL.
        try(var paths=java.nio.file.Files.newDirectoryStream(root)){
            long cutoffMillis=cutoff.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            for(Path entry:paths){String id=entry.getFileName().toString();if(id.matches("[a-f0-9-]{36}")&&!java.nio.file.Files.isSymbolicLink(entry)&&java.nio.file.Files.isRegularFile(entry)&&java.nio.file.Files.getLastModifiedTime(entry).toMillis()<cutoffMillis&&s.count("SELECT COUNT(*) FROM af_attachment WHERE id=?",id)==0)erase(id);}
        }catch(IOException e){org.slf4j.LoggerFactory.getLogger(Files.class).warn("Attachment directory cleanup will retry");}
        s.db.update("DELETE FROM af_upload_guard WHERE touched_at<?",s.now().minusDays(7));
    }
    public void bind(Object raw,User u,String owner,String type,long id,boolean mandatory){
        List<?> ids=raw instanceof List<?> list?list:List.of();require(ids.size()<=10,"一次最多10个附件");if(mandatory)require(!ids.isEmpty(),"请上传证明或现场材料");
        for(Object fileId:ids){Map<String,Object> f=s.one("SELECT * FROM af_attachment WHERE id=? FOR UPDATE",fileId);require(str(f,"owner_key").equals(hash(owner))||u!=null&&num(f,"user_id")==u.id(),"无法关联其他人的附件");conflict(f.get("entity_id")==null||str(f,"entity_type").equals(type)&&num(f,"entity_id")==id,"附件已关联其他单据");s.db.update("UPDATE af_attachment SET entity_type=?,entity_id=? WHERE id=?",type,id,fileId);}
    }
    public List<Map<String,Object>> list(String type,long id){return s.rows("SELECT id,filename,mime,bytes FROM af_attachment WHERE entity_type=? AND entity_id=?",type,id);}
    public Map<String,Object> readable(String id,User u,String owner){Map<String,Object> f=s.one("SELECT * FROM af_attachment WHERE id=?",id);boolean allowed=f.get("entity_id")==null&&str(f,"owner_key").equals(hash(owner));if(u!=null){allowed|=u.role().equals("CENTER");String t=str(f,"entity_type");long e=num(f,"entity_id");if(t.equals("report")){allowed|=u.role().equals("PUBLIC")&&s.count("SELECT COUNT(*) FROM af_report WHERE id=? AND (owner_id=? OR victim_id=?)",e,u.id(),u.victimId()==0?-1:u.victimId())>0;if(u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_command c JOIN af_report r ON r.alert_id=c.alert_id WHERE r.id=? AND c.org_id=?",e,u.orgId())>0;if(u.role().equals("GRID"))allowed|=s.count("SELECT COUNT(*) FROM af_visit v JOIN af_report r ON r.alert_id=v.alert_id WHERE r.id=? AND v.community_id=? AND v.assignee_id=?",e,u.communityId(),u.id())>0;}else if(t.equals("visit")&&u.role().equals("GRID"))allowed|=s.count("SELECT COUNT(*) FROM af_visit WHERE id=? AND community_id=? AND assignee_id=?",e,u.communityId(),u.id())>0;else if(t.equals("receipt")&&u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_receipt r JOIN af_command c ON r.command_id=c.id WHERE r.id=? AND c.org_id=?",e,u.orgId())>0;else if(t.equals("application")){allowed|=u.role().equals("PUBLIC")&&s.count("SELECT COUNT(*) FROM af_application WHERE id=? AND owner_id=?",e,u.id())>0;if(u.role().equals("PAYMENT"))allowed|=s.count("SELECT COUNT(*) FROM af_command WHERE application_id=? AND org_id=?",e,u.orgId())>0;}}
        if(!allowed)throw new Problem(404,"附件不存在或无权访问");return f;
    }
}

package cn.course.antifraud;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;
import static cn.course.antifraud.Support.*;

@RestControllerAdvice
public class Errors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(Problem.class) ResponseEntity<?> business(Problem e){return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<?> input(Exception e){return ResponseEntity.badRequest().body(Map.of("message","输入格式不正确，请检查字段"));}
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> file(Exception e){return ResponseEntity.status(413).body(Map.of("message","单文件不能超过10MB"));}
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("message","资源不存在"));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> duplicate(Exception e){return ResponseEntity.status(409).body(Map.of("message","记录已存在、关联无效或当前状态冲突，请刷新后重试"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception e){String id=java.util.UUID.randomUUID().toString().substring(0,8);log.error("request-failure {}",id,e);return ResponseEntity.status(500).body(Map.of("message","操作未完成，请稍后重试；错误编号 "+id));}
}

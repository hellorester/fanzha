package cn.course.antifraud;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.course.antifraud.Support.*;

class CommunityTest {
 private final User grid=new User(4,"grid1","网格员","GRID",0,1,0,false,false);
 @Test void unclaimedTaskDoesNotRevealResidentOrProcessDetails(){
  var support=mock(Support.class);var files=mock(Files.class);
  when(support.one(anyString(),eq(8L))).thenReturn(map("id",8,"community_id",1,"assignee_id",null,"state","待接收","victim_id",99,"process_text","private","lat",30));
  var result=new Community(support,files).visit(grid,8);
  assertEquals(true,result.get("restricted"));assertFalse(result.containsKey("victim_id"));assertFalse(result.containsKey("process_text"));assertFalse(result.containsKey("lat"));assertFalse(result.containsKey("person"));verifyNoInteractions(files);
 }
 @Test void anotherAssigneesTaskCannotBeReadEvenWithinSameCommunity(){
  var support=mock(Support.class);var files=mock(Files.class);
  when(support.one(anyString(),eq(8L))).thenReturn(map("id",8,"community_id",1,"assignee_id",7,"state","待上门","victim_id",99));
  assertEquals(404,assertThrows(Problem.class,()->new Community(support,files).visit(grid,8)).status);verifyNoInteractions(files);
 }
}

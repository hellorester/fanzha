package cn.course.antifraud;

import java.math.*;
import java.net.URI;
import java.net.IDN;
import java.time.*;
import java.util.*;
import static cn.course.antifraud.Support.*;

public final class Rules {
    private Rules(){}
    public static Map<String,Object> defaults(){return map("weights",List.of(25,15,15,30,15),"high",70,"medium",40,"mergeHours",24,"freezeHours",48,"extensionHours",48,"maxExtensions",2,"timeoutMinutes",30);}
    public static String normalize(String kind,String raw){
        String v=raw.strip();require(!v.isEmpty()&&v.length()<=500,"线索不能为空或过长");
        switch(kind){
            case "号码" -> {v=v.replaceAll("[\\s()（）-]","");if(v.startsWith("+86"))v=v.substring(3);else if(v.startsWith("0086"))v=v.substring(4);require(v.matches("[0-9]{7,15}"),"号码格式不正确");return v;}
            case "账户" -> {v=v.replaceAll("[\\s-]","");require(v.matches("[0-9]{6,32}"),"账户应为6至32位数字");return v;}
            case "网址" -> {return normalizeUrl(v);}
            default -> throw new Problem(400,"请选择号码、账户或网址");
        }
    }
    private static String normalizeUrl(String value){
        try{
            URI uri=URI.create(value.contains("://")?value:"https://"+value);
            String scheme=Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT),authority=uri.getRawAuthority();
            require(Set.of("http","https").contains(scheme)&&authority!=null&&!authority.contains("@"),"网址格式不正确");
            String host,portText="";
            if(authority.startsWith("[")){
                int end=authority.indexOf(']');require(end>0&&uri.getHost()!=null,"网址格式不正确");host=authority.substring(0,end+1).toLowerCase(Locale.ROOT);
                String rest=authority.substring(end+1);require(rest.isEmpty()||rest.startsWith(":"),"网址端口无效");if(!rest.isEmpty())portText=rest.substring(1);
                require(rest.isEmpty()||!portText.isEmpty(),"网址端口无效");
            }else{
                int colon=authority.lastIndexOf(':');host=colon<0?authority:authority.substring(0,colon);
                if(colon>=0){portText=authority.substring(colon+1);require(!portText.isEmpty(),"网址端口无效");}
                // IDN conversion also normalizes international dot separators.
                host=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if(host.endsWith("."))host=host.substring(0,host.length()-1);
                require(!host.isEmpty()&&URI.create("http://"+host).getHost()!=null,"网址域名无效");
            }
            int port=-1;if(!portText.isEmpty()){require(portText.matches("[0-9]{1,5}"),"网址端口无效");port=Integer.parseInt(portText);require(port>=1&&port<=65535,"网址端口无效");}
            if(scheme.equals("http")&&port==80||scheme.equals("https")&&port==443)port=-1;
            String path=Objects.toString(uri.normalize().getRawPath(),"").replaceAll("/+$","");
            // Matching remains host + path; query strings/fragments do not change a risk entry.
            return host+(port<0?"":":"+port)+path;
        }catch(IllegalArgumentException e){throw new Problem(400,"网址格式不正确");}
    }
    private static List<Integer> weights(Object raw){
        require(raw instanceof List<?>,"权重必须为五项整数");List<?> values=(List<?>)raw;require(values.size()==5,"权重必须恰好五项");
        List<Integer> result=new ArrayList<>();int sum=0;
        for(Object value:values){int n=integer(value,1,100,"权重须为1至100的整数");result.add(n);sum+=n;}
        require(sum==100,"权重之和必须为100");return result;
    }
    private static int integer(Object value,int min,int max,String message){
        try{int n=new BigDecimal(Objects.toString(value,"")).intValueExact();require(n>=min&&n<=max,message);return n;}
        catch(NumberFormatException|ArithmeticException e){throw new Problem(400,message);}
    }
    @SuppressWarnings("unchecked") public static void validateConfig(Map<String,Object> c){
        weights(c.get("weights"));
        int medium=integer(c.get("medium"),1,100,"中风险阈值须为1至100的整数"),high=integer(c.get("high"),1,100,"高风险阈值须为1至100的整数");require(high>medium,"高风险阈值须大于中风险阈值");
        for(String k:List.of("mergeHours","freezeHours","extensionHours","timeoutMinutes"))integer(c.get(k),1,1680,"时限必须为1至1680的正整数");
        integer(c.get("maxExtensions"),0,10,"延期上限须为0至10的整数");
    }
    public static Map<String,Object> score(Map<String,Object> input,boolean hit,Map<String,Object> config){
        List<Integer> weights=new ArrayList<>();boolean fallback=false;
        try{weights=weights(config.get("weights"));}catch(Problem e){weights=List.of(25,15,15,30,15);fallback=true;}
        int amountScore=0,timeScore=0;
        if(!str(input,"amount").isEmpty()){BigDecimal a=amount(input,"amount");require(a.signum()>=0,"金额不能为负数");amountScore=a.compareTo(new BigDecimal("1000"))<0?20:a.compareTo(new BigDecimal("5000"))<0?50:a.compareTo(new BigDecimal("20000"))<0?75:100;}
        if(!str(input,"transactionTime").isEmpty()){LocalTime t;try{t=LocalTime.parse(str(input,"transactionTime"));}catch(Exception e){throw new Problem(400,"交易时间格式不正确");}timeScore=t.isBefore(LocalTime.of(5,0))||!t.isBefore(LocalTime.of(23,0))?100:30;}
        int abnormal=yes(input,"newLarge")?100:yes(input,"repeat")?80:0;
        List<Integer> factors=List.of(amountScore,timeScore,yes(input,"remote")?100:0,hit?100:0,abnormal);
        List<String> names=List.of("交易金额","交易时段","异地交易","黑名单","异常行为");List<Map<String,Object>> details=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<5;i++){BigDecimal weighted=BigDecimal.valueOf((long)weights.get(i)*factors.get(i)).divide(BigDecimal.valueOf(100));total=total.add(weighted);details.add(map("name",names.get(i),"factor",factors.get(i),"weight",weights.get(i),"contribution",weighted));}
        int score=Math.clamp(total.setScale(0,RoundingMode.HALF_UP).intValue(),0,100);long high=num(config,"high"),medium=num(config,"medium");if(high<=medium||high>100||medium<1){high=70;medium=40;fallback=true;}
        return map("score",score,"level",score>=high?"高":score>=medium?"中":"低","details",details,"fallback",fallback,"hit",hit);
    }
}

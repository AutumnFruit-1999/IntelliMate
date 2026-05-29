package com.atm.intellimate.gateway.channel.wechat;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeChatXmlParser {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("<(\\w+)><!\\[CDATA\\[(.+?)]]></\\1>|<(\\w+)>(\\d+)</\\3>");

    /**
     * 将微信 XML 消息解析为 Map。
     */
    public static Map<String, String> parse(String xml) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = FIELD_PATTERN.matcher(xml);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                result.put(matcher.group(1), matcher.group(2));
            } else if (matcher.group(3) != null) {
                result.put(matcher.group(3), matcher.group(4));
            }
        }
        return result;
    }

    /**
     * 构建被动回复 XML 消息（文本类型）。
     */
    public static String buildTextReply(String toUser, String fromUser, String content) {
        long timestamp = System.currentTimeMillis() / 1000;
        return String.format("""
                <xml>
                <ToUserName><![CDATA[%s]]></ToUserName>
                <FromUserName><![CDATA[%s]]></FromUserName>
                <CreateTime>%d</CreateTime>
                <MsgType><![CDATA[text]]></MsgType>
                <Content><![CDATA[%s]]></Content>
                </xml>""", toUser, fromUser, timestamp, content);
    }
}

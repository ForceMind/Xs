package dev.forcemind.xs.tests;
import dev.forcemind.xs.core.TimelineBufferSchema;
public final class TimelineSchemaDump {
    public static void main(String[] args){for(String sql:TimelineBufferSchema.CREATE)System.out.println(sql);}
}

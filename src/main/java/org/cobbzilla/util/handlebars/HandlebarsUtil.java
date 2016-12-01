package org.cobbzilla.util.handlebars;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.io.AbstractTemplateLoader;
import com.github.jknack.handlebars.io.StringTemplateSource;
import com.github.jknack.handlebars.io.TemplateSource;
import lombok.AllArgsConstructor;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.reflect.ReflectionUtil;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.cobbzilla.util.daemon.ZillaRuntime.*;
import static org.cobbzilla.util.string.StringUtil.*;

@AllArgsConstructor @Slf4j
public class HandlebarsUtil extends AbstractTemplateLoader {

    public static final DateTimeFormatter DATE_FORMAT_MMDDYYYY = DateTimeFormat.forPattern("MM/dd/yyyy");
    public static final DateTimeFormatter DATE_FORMAT_MMMM_D_YYYY = DateTimeFormat.forPattern("MMMM d, yyyy");
    public static final DateTimeFormatter DATE_FORMAT_YYYY_MM_DD = DateTimeFormat.forPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATE_FORMAT_MMM_DD_YYYY = DateTimeFormat.forPattern("MMM dd, yyyy");

    // For now only m (months) and d (days) are supported to add to datetime values within Handlebars (both has to be
    // present at the same time in that same order, but the value for each can be 0 to exclude that one - i.e. 0m15d).
    public static final PeriodFormatter PERIOD_FORMATTER = new PeriodFormatterBuilder()
            .appendMonths().appendSuffix("m").appendDays().appendSuffix("d").toFormatter();

    private String sourceName = "unknown";

    public static Map<String, Object> apply(Handlebars handlebars, Map<String, Object> map, Map<String, Object> ctx) {
        final Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof String) {
                final String val = (String) value;
                if (val.contains("{{") && val.contains("}}")) {
                    merged.put(entry.getKey(), apply(handlebars, value.toString(), ctx));
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }

            } else if (value instanceof Map) {
                merged.put(entry.getKey(), apply(handlebars, (Map<String, Object>) value, ctx));

            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    public static String apply(Handlebars handlebars, String value, Map<String, Object> ctx) {
        try {
            @Cleanup final StringWriter writer = new StringWriter(value.length());
            handlebars.compile(value).apply(ctx, writer);
            return writer.toString();

        } catch (Exception e) {
            return die("apply: "+e, e);
        } catch (Error e) {
            log.warn("apply: "+e, e);
            throw e;
        }
    }

    public static <T> T applyReflectively(Handlebars handlebars, T thing, Map<String, Object> ctx) {
        for (Method m : thing.getClass().getMethods()) {
            if (m.getName().startsWith("get") && m.getReturnType().equals(String.class)) {
                try {
                    final Method setter = thing.getClass().getMethod(ReflectionUtil.setterForGetter(m.getName()), String.class);
                    Object value = m.invoke(thing, null);
                    if (value != null && value instanceof String && value.toString().contains("{{")) {
                        setter.invoke(thing, apply(handlebars, (String) value, ctx));
                    }
                } catch (Exception e) {
                    // no setter for getter
                    log.warn("applyReflectively: "+e);
                }
            }
        }
        return thing;
    }

    @Override public TemplateSource sourceAt(String source) throws IOException {
        return new StringTemplateSource(sourceName, source);
    }

    public static void registerCurrencyHelpers(Handlebars hb) {
        hb.registerHelper("dollarsNoSign", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) return "";
                return new Handlebars.SafeString(formatDollarsNoSign(longVal(src)));
            }
        });

        hb.registerHelper("dollarsWithSign", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) return "";
                return new Handlebars.SafeString(formatDollarsWithSign(longVal(src)));
            }
        });

        hb.registerHelper("dollarsAndCentsNoSign", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) return "";
                return new Handlebars.SafeString(formatDollarsAndCentsNoSign(longVal(src)));
            }
        });

        hb.registerHelper("dollarsAndCentsWithSign", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) return "";
                return new Handlebars.SafeString(formatDollarsAndCentsWithSign(longVal(src)));
            }
        });
    }

    public static void registerDateHelpers(Handlebars hb) {
        hb.registerHelper("date_short", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) src = "now";
                return new Handlebars.SafeString(DATE_FORMAT_MMDDYYYY.print(longVal(src)));
            }
        });

        hb.registerHelper("date_yyyy_mm_dd", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) src = "now";
                return new Handlebars.SafeString(DATE_FORMAT_YYYY_MM_DD.print(longVal(src)));
            }
        });

        hb.registerHelper("date_mmm_dd_yyyy", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) src = "now";
                return new Handlebars.SafeString(DATE_FORMAT_MMM_DD_YYYY.print(longVal(src)));
            }
        });

        hb.registerHelper("date_long", new Helper<Object>() {
            public CharSequence apply(Object src, Options options) {
                if (empty(src)) src = "now";
                return new Handlebars.SafeString(DATE_FORMAT_MMMM_D_YYYY.print(longVal(src)));
            }
        });
    }

    public static long longVal(Object src) {
        if (src == null) return now();
        String srcStr = src.toString().trim();

        if (srcStr == "" || srcStr == "0" || srcStr == "now") return now();

        if (srcStr.startsWith("now")) {
            // Multiple periods may be added to the original timestamp (separated by comma), but in the correct order.
            String[] splitSrc = srcStr.substring(3).split(",");
            DateTime result = new DateTime(now());
            for (String period : splitSrc) {
                result = result.plus(Period.parse(period, PERIOD_FORMATTER));
            }
            return result.getMillis();
        }

        return ((Number) src).longValue();
    }

}

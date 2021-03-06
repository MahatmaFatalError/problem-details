package com.github.t1.problemdetail.ri.lib;

import com.github.t1.problemdetail.Detail;
import com.github.t1.problemdetail.Extension;
import com.github.t1.problemdetail.Instance;
import com.github.t1.problemdetail.LogLevel;
import com.github.t1.problemdetail.Logging;
import com.github.t1.problemdetail.Status;
import com.github.t1.problemdetail.Title;
import com.github.t1.problemdetail.Type;
import lombok.Getter;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response.StatusType;
import javax.ws.rs.core.UriBuilder;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static com.github.t1.problemdetail.LogLevel.AUTO;
import static java.util.stream.Collectors.joining;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.Family.CLIENT_ERROR;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

/**
 * Tech stack independent collector. Template methods can be overridden to provide tech stack specifics.
 */
public abstract class ProblemDetails {
    public static final String URN_PROBLEM_TYPE_PREFIX = "urn:problem-type:";

    protected final Throwable exception;
    protected final @NonNull Class<? extends Throwable> exceptionType;

    @Getter(lazy = true) private final StatusType status = buildStatus();
    @Getter(lazy = true) private final Object body = buildBody();
    @Getter(lazy = true) private final String mediaType = buildMediaType();
    @Getter(lazy = true) private final String logMessage = buildLogMessage();

    public ProblemDetails(Throwable exception) {
        this.exception = exception;
        this.exceptionType = exception.getClass();
    }

    protected Object buildBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", buildTypeUri());

        body.put("title", buildTitle());

        body.put("status", getStatus().getStatusCode());

        String detail = buildDetail();
        if (detail != null) {
            body.put("detail", detail);
        }

        body.put("instance", buildInstance());

        body.putAll(buildExtensions());

        return body;
    }

    protected StatusType buildStatus() {
        if (exceptionType != null && exceptionType.isAnnotationPresent(Status.class)) {
            return exceptionType.getAnnotation(Status.class).value();
        } else if (exception instanceof IllegalArgumentException) {
            return BAD_REQUEST;
        } else {
            return fallbackStatus();
        }
    }

    protected StatusType fallbackStatus() {
        return INTERNAL_SERVER_ERROR;
    }

    protected URI buildTypeUri() {
        return buildTypeUri(exceptionType);
    }

    public static URI buildTypeUri(Class<? extends Throwable> type) {
        return URI.create(type.isAnnotationPresent(Type.class)
            ? type.getAnnotation(Type.class).value()
            : URN_PROBLEM_TYPE_PREFIX + wordsFromTypeName(type, '-').toLowerCase());
    }

    protected String buildTitle() {
        return exceptionType.isAnnotationPresent(Title.class)
            ? exceptionType.getAnnotation(Title.class).value()
            : wordsFromTypeName(exceptionType, ' ');
    }

    private static String wordsFromTypeName(Class<? extends Throwable> type, char delimiter) {
        String message = camelToWords(type.getSimpleName(), delimiter);
        if (message.endsWith(delimiter + "Exception"))
            message = message.substring(0, message.length() - 10);
        return message;
    }

    private static String camelToWords(String input, char delimiter) {
        StringBuilder out = new StringBuilder();
        input.codePoints().forEach(c -> {
            if (Character.isUpperCase(c) && out.length() > 0) {
                out.append(delimiter);
            }
            out.appendCodePoint(c);
        });
        return out.toString();
    }

    protected String buildDetail() {
        List<Object> details = new ArrayList<>();
        for (Method method : exceptionType.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Detail.class)) {
                details.add(invoke(method));
            }
        }
        for (Field field : exceptionType.getDeclaredFields()) {
            if (field.isAnnotationPresent(Detail.class)) {
                details.add(get(field));
            }
        }
        return (details.isEmpty())
            ? hasDefaultMessage() ? null : exception.getMessage()
            : details.stream().map(Object::toString).collect(joining(". "));
    }

    /** We don't want to repeat default messages like `400 Bad Request` */
    protected abstract boolean hasDefaultMessage();

    @Nullable private Object invoke(Method method) {
        try {
            if (method.getParameterCount() != 0)
                return invocationFailed(method, "expected no args but got " + method.getParameterCount());
            method.setAccessible(true);
            return method.invoke(exception);
        } catch (IllegalAccessException e) {
            return invocationFailed(method, e);
        } catch (InvocationTargetException e) {
            return invocationFailed(method, e.getTargetException());
        }
    }

    private String invocationFailed(Method method, Object detail) {
        return "could not invoke " + method.getDeclaringClass().getSimpleName()
            + "." + method.getName() + ": " + detail;
    }

    @Nullable private Object get(Field field) {
        try {
            field.setAccessible(true);
            return field.get(exception);
        } catch (IllegalAccessException e) {
            return "could not get " + field;
        }
    }

    protected URI buildInstance() {
        String instance = Arrays.stream(exceptionType.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Instance.class))
            .map(this::get)
            .filter(Objects::nonNull)
            .findAny()
            .map(Object::toString)
            .orElseGet(
                () -> Arrays.stream(exceptionType.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Instance.class))
                    .map(this::invoke)
                    .filter(Objects::nonNull)
                    .findAny()
                    .map(Object::toString)
                    .orElseGet(
                        () -> "urn:uuid:" + UUID.randomUUID()));
        return createSafeUri(instance);
    }

    private URI createSafeUri(String string) {
        try {
            return new URI(string);
        } catch (URISyntaxException e) {
            try {
                return new URI("urn:" + string.replace(' ', '+'));
            } catch (URISyntaxException ee) {
                return UriBuilder.fromUri("urn:invalid-uri-syntax")
                    .queryParam("source", string)
                    .queryParam("exception", e)
                    .build();
            }
        }
    }

    protected Map<String, Object> buildExtensions() {
        Map<String, Object> extensions = new TreeMap<>();
        for (Method method : exceptionType.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Extension.class)) {
                extensions.put(extensionName(method), invoke(method));
            }
        }
        for (Field field : exceptionType.getDeclaredFields()) {
            if (field.isAnnotationPresent(Extension.class)) {
                extensions.put(extensionName(field), get(field));
            }
        }
        return extensions;
    }

    private String extensionName(Member member) {
        String annotatedName = ((AnnotatedElement) member).getAnnotation(Extension.class).value();
        return annotatedName.isEmpty() ? member.getName() : annotatedName;
    }

    protected String buildMediaType() {
        String format = findMediaTypeSubtype();

        // browsers send, e.g., `text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8`
        // so the extra `problem+` is acceptable only by the wildcard and that starts a download
        return "xhtml+xml".equals(format) ? "text/html" : "application/problem+" + format;
    }

    protected abstract String findMediaTypeSubtype();

    private String buildLogMessage() {
        return "ProblemDetail:\n" + formatBody() + "\n"
            + "Exception";
    }

    private Object formatBody() {
        return (body instanceof Map)
            ? ((Map<?, ?>) body).entrySet().stream()
            .map(entry -> "  " + entry.getKey() + ": " + entry.getValue())
            .collect(joining("\n"))
            : String.valueOf(body);
    }


    public ProblemDetails log() {
        log(buildLogMessage());
        return this;
    }

    private void log(String message) {
        Logger logger = buildLogger();
        switch (buildLogLevel()) {
            case AUTO:
                if (CLIENT_ERROR.equals(getStatus().getFamily())) {
                    logger.debug(message);
                } else {
                    logger.error(message, exception);
                }
                break;
            case ERROR:
                logger.error(message, exception);
                break;
            case WARNING:
                logger.warn(message, exception);
                break;
            case INFO:
                logger.info(message);
                break;
            case DEBUG:
                logger.debug(message);
                break;
            case OFF:
                break;
        }
    }

    private Logger buildLogger() {
        Logging logging = findLoggingAnnotation();
        return (logging == null || logging.to().isEmpty()) ? LoggerFactory.getLogger(exceptionType)
            : LoggerFactory.getLogger(logging.to());
    }

    private LogLevel buildLogLevel() {
        Logging logging = findLoggingAnnotation();
        return (logging == null) ? AUTO : logging.at();
    }

    private Logging findLoggingAnnotation() {
        Logging onType = exceptionType.getAnnotation(Logging.class);
        Logging onPackage = exceptionType.getPackage().getAnnotation(Logging.class);
        if (onPackage == null)
            return onType;
        if (onType == null)
            return onPackage;
        return new Logging() {
            @Override public Class<? extends Annotation> annotationType() {
                throw new UnsupportedOperationException();
            }

            @Override public String to() {
                return (onType.to().isEmpty()) ? onPackage.to() : onType.to();
            }

            @Override public LogLevel at() {
                return (onType.at() == AUTO) ? onPackage.at() : onType.at();
            }
        };
    }
}

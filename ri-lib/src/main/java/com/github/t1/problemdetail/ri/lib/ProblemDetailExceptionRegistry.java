package com.github.t1.problemdetail.ri.lib;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.github.t1.problemdetail.ri.lib.ProblemDetails.URN_PROBLEM_TYPE_PREFIX;
import static com.google.common.base.CaseFormat.LOWER_HYPHEN;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;

@Slf4j
public class ProblemDetailExceptionRegistry extends Throwable {
    static final Map<String, Class<? extends RuntimeException>> REGISTRY = new HashMap<>();

    public static String register(Class<? extends RuntimeException> exceptionType) {
        String typeUri = ProblemDetails.buildTypeUri(exceptionType).toString();
        REGISTRY.put(typeUri, exceptionType);
        return typeUri;
    }

    static Class<? extends RuntimeException> computeFrom(String type) {
        if (type != null && type.startsWith(URN_PROBLEM_TYPE_PREFIX)) {
            return computeFromUrn(type.substring(URN_PROBLEM_TYPE_PREFIX.length()));
        } else {
            return null;
        }
    }

    private static Class<? extends RuntimeException> computeFromUrn(String type) {
        String camel = LOWER_HYPHEN.to(UPPER_CAMEL, type);
        Class<? extends RuntimeException> cls = forName("java.lang." + camel + "Exception");
        if (cls != null)
            return cls;
        return forName("javax.ws.rs." + camel + "Exception");
    }

    private static Class<? extends RuntimeException> forName(String name) {
        try {
            Class<?> t = Class.forName(name);
            //noinspection unchecked
            return RuntimeException.class.isAssignableFrom(t)
                ? (Class<? extends RuntimeException>) t
                : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
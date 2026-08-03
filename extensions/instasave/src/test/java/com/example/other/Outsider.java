package com.example.other;

/**
 * An object outside every namespace the resolver is willing to reflect over.
 *
 * <p>Deliberately in a package that is not {@code com.instagram}, {@code com.facebook} or an
 * obfuscated {@code X} namespace, so that reading its fields would mean the package gate had
 * stopped working.
 */
public final class Outsider {
    public String url;
    public Object nested;

    public Outsider(String url) {
        this.url = url;
    }
}

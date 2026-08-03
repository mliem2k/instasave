package com.instagram.test;

import java.util.List;

/**
 * Stand ins for the Instagram model objects the resolver walks.
 *
 * <p>The package matters and is part of what these tests cover. {@code MediaUrlResolver}
 * deliberately refuses to descend into anything outside {@code com.instagram}, {@code com.facebook}
 * and the obfuscated {@code X} namespaces, so fakes declared anywhere else would be skipped and
 * every test would pass while exercising nothing. Nested classes are fine because their binary
 * name still starts with the outer package.
 *
 * <p>The interface names matter too: the resolver matches {@code ImageUrl} and
 * {@code VideoVersionIntf} by simple name, which is what lets it survive obfuscation.
 */
public final class Fakes {

    private Fakes() {
    }

    public interface ImageUrl {
        String getUrl();

        int getWidth();

        int getHeight();
    }

    public interface VideoVersionIntf {
        String getUrl();

        int getWidth();
    }

    public static final class Image implements ImageUrl {
        private final String url;
        private final int width;
        private final int height;

        public Image(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        @Override public String getUrl() { return url; }

        @Override public int getWidth() { return width; }

        @Override public int getHeight() { return height; }
    }

    public static final class Video implements VideoVersionIntf {
        private final String url;
        private final int width;

        public Video(String url, int width) {
            this.url = url;
            this.width = width;
        }

        @Override public String getUrl() { return url; }

        @Override public int getWidth() { return width; }
    }

    /** A media model: some candidates, an owner, and an id, reachable only through fields. */
    public static final class Media {
        public List<Object> candidates;
        public Object owner;
        private final String id;

        public Media(String id, List<Object> candidates, Object owner) {
            this.id = id;
            this.candidates = candidates;
            this.owner = owner;
        }

        public String getId() { return id; }
    }

    public static final class User {
        private final String username;

        public User(String username) { this.username = username; }

        public String getUsername() { return username; }
    }

    /** Wrapper used to bury media further down the graph, and to build reference cycles. */
    public static final class Holder {
        public Object value;
        public Holder self;

        public Holder(Object value) { this.value = value; }
    }
}

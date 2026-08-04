package com.instagram.test;

import java.util.Collections;
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
        /** A Pando/LiveTree media dictionary hung off the model, reached by the field walk. */
        public Object mediaDict;
        private final String id;

        public Media(String id, List<Object> candidates, Object owner) {
            this.id = id;
            this.candidates = candidates;
            this.owner = owner;
        }

        public String getId() { return id; }
    }

    /**
     * Simple name contains "MediaDict", so {@code MediaUrlResolver.isMediaDict} matches anything
     * implementing it, exactly as it matches Instagram's own {@code MutableMediaDictIntf}.
     */
    public interface MediaDictIntf {
    }

    /**
     * A Pando/LiveTree style media dictionary, the shape behind the bug where a video story used to
     * save its still cover.
     *
     * <p>The cover still IS a plain field, so the resolver's field walk always finds it. The video
     * versions are NOT a field: they are produced only by the zero-arg {@link #videoVersions()}
     * accessor, mirroring {@code LiveTreeMediaDict.A9w()}, a native JNI accessor with no backing
     * Java field. The video URL and width are held as a {@code char[]} and an {@code int}, both of
     * which the field walk provably skips (it never descends into primitives or primitive-component
     * arrays), so nothing about the video is reachable until the accessor materializes it. That is
     * what forces the resolver's second pass to run to see the video at all.
     */
    public static final class MediaDict implements MediaDictIntf {

        /** The cover still, reachable through the field walk. This used to win, wrongly. */
        public Object coverImage;

        // Video source data hidden from the field walk: a char[] (primitive-component array) and an
        // int (primitive) are both skipped by the walk, so only videoVersions() can reach the video.
        private final char[] videoUrl;
        private final int videoWidth;

        /** Set when videoVersions() runs, so a test can prove the second pass did (or did not) fire. */
        public boolean videoVersionsInvoked = false;
        /** Set if a non-List zero-arg accessor is wrongly invoked by the second pass. Must stay false. */
        public boolean nonListAccessorInvoked = false;
        /** Set if an arg-taking accessor is wrongly invoked by the second pass. Must stay false. */
        public boolean argAccessorInvoked = false;

        public MediaDict(String videoUrl, int videoWidth, Object coverImage) {
            this.videoUrl = videoUrl == null ? new char[0] : videoUrl.toCharArray();
            this.videoWidth = videoWidth;
            this.coverImage = coverImage;
        }

        /**
         * The ONLY path to the video: a zero-arg, List-returning accessor, exactly the shape the
         * resolver's second pass invokes. Materializes a fresh Video from the hidden buffer, or an
         * empty list when there genuinely is no video.
         */
        public List<Object> videoVersions() {
            videoVersionsInvoked = true;
            if (videoUrl.length == 0) {
                return Collections.emptyList();
            }
            return Collections.<Object>singletonList(new Video(new String(videoUrl), videoWidth));
        }

        /**
         * Author and tagged user, each behind its own zero-arg accessor returning the author type,
         * exactly as the real dictionary exposes them. Neither is a field, so only invoking these
         * reaches them. The accessors are deliberately indistinguishable by name, because the real
         * ones are obfuscated: telling the author from the tagged user is the resolver's job.
         */
        public com.instagram.user.model.User author;
        public com.instagram.user.model.User taggedUser;

        public com.instagram.user.model.User authorAccessor() {
            return author;
        }

        public com.instagram.user.model.User taggedUserAccessor() {
            return taggedUser;
        }

        /** Zero-arg but returns a String, not a List: the second pass must skip it by return type. */
        public String coverImageId() {
            nonListAccessorInvoked = true;
            throw new AssertionError("non-List zero-arg accessor must never be invoked by the dict pass");
        }

        /** Takes an argument: the second pass must skip it by parameter count. */
        public List<Object> versionAt(int index) {
            argAccessorInvoked = true;
            throw new AssertionError("arg-taking accessor must never be invoked by the dict pass");
        }
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

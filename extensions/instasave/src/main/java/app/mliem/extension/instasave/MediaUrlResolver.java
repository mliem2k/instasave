package app.mliem.extension.instasave;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a CDN media URL out of whatever live object the patched call site handed us.
 *
 * <p>Why reflection rather than a traced field path: Instagram stores GraphQL shaped data in a
 * compact binary tree (Pando / LiveTree) that is materialized through JNI, so static tracing of
 * {@code image_versions2} or {@code video_versions} bottoms out in native accessors and never
 * reaches a plain field. That is a limitation of reading smali, not of the running app. By the
 * time any UI callback fires, the tree has already been materialized into ordinary Java objects,
 * so a bounded walk over the live graph sees plain values.
 *
 * <p>Nothing here matches on field names, because those are obfuscated and churn every release.
 * It matches on things obfuscation does not touch: interface names that Instagram leaves intact
 * ({@code ImageUrl}, {@code VideoVersionIntf}), accessor method names on those interfaces, and
 * the shape of the values themselves.
 */
public final class MediaUrlResolver {

    /** Depth beyond the entry object. Six levels reaches media models from a controller. */
    private static final int MAX_DEPTH = 6;

    /** Hard ceiling so a pathological graph cannot stall the UI thread. */
    private static final int MAX_VISITED = 6000;

    /** Interfaces Instagram does not obfuscate, matched by simple name. */
    private static final String IMAGE_URL_INTERFACE = "ImageUrl";
    private static final String VIDEO_VERSION_INTERFACE = "VideoVersion";

    /** The media author's type. Unobfuscated, so it is matched exactly. */
    private static final String USER_CLASS = "com.instagram.user.model.User";

    /**
     * Name of the method on {@code User} that returns the account handle, injected by the patch.
     *
     * <p>Instagram exposes no {@code getUsername()} anywhere, and the real getter carries an
     * obfuscated name that changes between releases, so it cannot be called by name from here.
     * The patch resolves it at build time by a stable field id and passes the name across, which
     * keeps a version specific detail out of this code. Null means the patch could not resolve
     * it, in which case files are saved without an account name rather than with a wrong one.
     */
    private static volatile String usernameAccessor;

    private MediaUrlResolver() {
    }

    /** Injected once at startup by the patch. */
    public static void setUsernameAccessor(String methodName) {
        usernameAccessor = methodName;
        InstaSave.log("username accessor resolved: " + methodName);
    }

    /** A single ranked media URL together with whatever metadata came with it. */
    public static final class Candidate {
        public final String url;
        public final boolean video;
        /** Pixel area for images, or bitrate/width for videos. Used only for ranking. */
        public final long weight;

        Candidate(String url, boolean video, long weight) {
            this.url = url;
            this.video = video;
            this.weight = weight;
        }
    }

    /** Everything the downloader needs about one piece of media. */
    public static final class Resolved {
        public final String url;
        public final boolean video;
        public final String username;
        public final String mediaId;
        /** All candidates found, best first. Kept so a carousel index can select among them. */
        public final List<Candidate> candidates;

        Resolved(String url, boolean video, String username, String mediaId, List<Candidate> candidates) {
            this.url = url;
            this.video = video;
            this.username = username;
            this.mediaId = mediaId;
            this.candidates = candidates;
        }
    }

    /**
     * Walks the object graph rooted at {@code root} and returns the best media URL found,
     * or null if the walk turned up nothing usable.
     */
    public static Resolved resolve(Object root) {
        return resolve(root, -1);
    }

    /**
     * @param carouselIndex zero based index into the discovered candidates, or a negative
     *                      value to take the highest ranked one. Used for multi image posts,
     *                      where the graph holds every slide but only one is on screen.
     */
    public static Resolved resolve(Object root, int carouselIndex) {
        Walk walk = new Walk();
        try {
            walk.run(root);
        } catch (Throwable t) {
            InstaSave.log("resolve walk aborted", t);
        }

        // Neither the video nor the author is a plain field: both live behind native-tree
        // accessors, which is why a field-only walk finds the cover image and no account name.
        // Invoke those accessors for whichever of the two is still missing. Paid only on a tap.
        boolean wantVideo = !walk.hasVideo();
        boolean wantUser = walk.username == null;
        if (wantVideo || wantUser) {
            try {
                walk.materializeFromDicts(wantVideo, wantUser);
            } catch (Throwable t) {
                InstaSave.log("media dict materialize aborted", t);
            }
        }
        try {
            walk.resolveUsername();
        } catch (Throwable t) {
            InstaSave.log("username resolve aborted", t);
        }

        List<Candidate> ranked = walk.rank();
        if (ranked.isEmpty()) {
            // Last resort: a URL an image view bound recently. Covers the case where the
            // click handler we hooked is a synthetic class that captured nothing useful.
            String recent = ImageViewRegistry.mostRecentUrl();
            if (recent == null) {
                return null;
            }
            InstaSave.log("resolve fell back to the most recently bound image URL");
            ranked = new ArrayList<>();
            ranked.add(new Candidate(recent, isVideoUrl(recent), 0L));
        }

        Candidate chosen = ranked.get(0);

        // The best video is ranked first (videos before images, highest resolution first). When
        // the user has turned the quality preference off, save the smallest video instead, which
        // is the data saver choice. Applied only to a single video, not to a carousel selection.
        if (chosen.video && carouselIndex < 0 && !Settings.highestQuality()) {
            for (Candidate candidate : ranked) {
                if (candidate.video && candidate.weight < chosen.weight) {
                    chosen = candidate;
                }
            }
        }

        if (carouselIndex >= 0) {
            // Slides of one carousel are all the same media kind, so index within that kind.
            List<Candidate> sameKind = new ArrayList<>();
            for (Candidate candidate : ranked) {
                if (candidate.video == chosen.video) {
                    sameKind.add(candidate);
                }
            }
            if (carouselIndex < sameKind.size()) {
                chosen = sameKind.get(carouselIndex);
            }
        }

        return new Resolved(chosen.url, chosen.video, walk.username, walk.mediaId, ranked);
    }

    // region graph walk

    private static final class Node {
        final Object value;
        final int depth;

        Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static final class Walk {
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private final ArrayDeque<Node> queue = new ArrayDeque<>();
        private final List<Candidate> images = new ArrayList<>();
        private final List<Candidate> videos = new ArrayList<>();
        private final Set<String> seenUrls = new HashSet<>();

        // Pando/LiveTree media dictionaries met during the field walk. Neither the video-version
        // list nor the author is a field, so both are only materialized in a second pass
        // (materializeFromDicts).
        private final List<Object> mediaDicts = new ArrayList<>();

        /** Candidate authors. A post can carry several (author, coauthors, tagged users). */
        private final List<Object> users = new ArrayList<>();

        String username;
        String mediaId;

        boolean hasVideo() {
            return !videos.isEmpty();
        }

        void run(Object root) {
            if (root == null) {
                return;
            }
            queue.add(new Node(root, 0));

            while (!queue.isEmpty() && visited.size() < MAX_VISITED) {
                Node node = queue.poll();
                Object value = node.value;
                if (value == null || node.depth > MAX_DEPTH) {
                    continue;
                }
                if (!visited.add(value)) {
                    continue;
                }
                visit(value, node.depth);
            }
        }

        private void visit(Object value, int depth) {
            Class<?> type = value.getClass();

            if (value instanceof String) {
                addRawUrl((String) value);
                return;
            }
            if (value instanceof CharSequence) {
                addRawUrl(value.toString());
                return;
            }

            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    int length = Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        enqueue(Array.get(value, i), depth + 1);
                    }
                }
                return;
            }
            if (value instanceof Collection) {
                for (Object element : (Collection<?>) value) {
                    enqueue(element, depth + 1);
                }
                return;
            }
            if (value instanceof Map) {
                for (Object element : ((Map<?, ?>) value).values()) {
                    enqueue(element, depth + 1);
                }
                return;
            }

            if (!isAppClass(type)) {
                return;
            }

            harvestTypedAccessors(value, type);
            harvestMetadata(value, type);
            if (isMediaDict(type)) {
                mediaDicts.add(value);
            }
            if (USER_CLASS.equals(type.getName())) {
                // Some surfaces hold the author directly rather than behind the dict.
                users.add(value);
            }

            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                Field[] fields;
                try {
                    fields = current.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    if (fieldType.isPrimitive()) {
                        continue;
                    }
                    Object child;
                    try {
                        field.setAccessible(true);
                        child = field.get(value);
                    } catch (Throwable t) {
                        continue;
                    }
                    enqueue(child, depth + 1);
                }
            }
        }

        private void enqueue(Object value, int depth) {
            if (value == null || depth > MAX_DEPTH) {
                return;
            }
            queue.add(new Node(value, depth));
        }

        /**
         * Reads Instagram's unobfuscated media interfaces. {@code ImageUrl} exposes
         * getUrl/getWidth/getHeight and {@code VideoVersionIntf} exposes getUrl, so an object
         * implementing either yields a URL without any field name knowledge.
         */
        private void harvestTypedAccessors(Object value, Class<?> type) {
            boolean image = implementsInterfaceNamed(type, IMAGE_URL_INTERFACE);
            boolean video = implementsInterfaceNamed(type, VIDEO_VERSION_INTERFACE);
            if (!image && !video) {
                return;
            }

            String url = asString(invokeNoArg(value, "getUrl"));
            if (!isMediaUrl(url)) {
                return;
            }

            long weight;
            if (video) {
                addCandidate(new Candidate(url, true, videoResolutionWeight(value)));
            } else {
                Integer width = asInt(invokeNoArg(value, "getWidth"));
                Integer height = asInt(invokeNoArg(value, "getHeight"));
                weight = (width != null && height != null)
                        ? (long) width * (long) height
                        : 0L;
                addCandidate(new Candidate(url, isVideoUrl(url), weight));
            }
        }

        /**
         * Materializes video versions that live behind the Pando/LiveTree dictionary rather than
         * in a plain field.
         *
         * <p>This is the crux of why a story used to save a still. Instagram stores
         * {@code video_versions} inside a native LiveTree, exposed only through a zero-argument
         * accessor on {@code LiveTreeMediaDict} (for example {@code A9w()}). Nothing materializes
         * those objects until that method is called, so a field-only walk never sees a
         * {@code VideoVersionIntf}, while the cover image, which IS a plain field on the media
         * model, is always found and wins the fallback.
         *
         * <p>So when the field walk turned up no video, invoke every zero-argument
         * {@code List}-returning accessor on each media-dict object and harvest any element
         * implementing {@code VideoVersionIntf}. This is deliberately narrow: it only runs on the
         * dict object itself (one or two per graph, matched by an unobfuscated interface name),
         * only invokes zero-arg {@code List} getters, and stops at the first video list it finds.
         *
         * <p>Note it does invoke every zero-arg {@code List} getter on the dict, not only the
         * video one (image_versions, tagged users, product tags, and so on), fast-failing on the
         * first-element interface check. Those are all native tree reads on the dict itself, which
         * are pure, so this is safe; it is not invoking arbitrary getters across the graph. The
         * cost is bounded and paid only on a user tap, and only when no video was field-reachable.
         */
        void materializeFromDicts(boolean wantVideo, boolean wantUser) {
            boolean stillWantVideo = wantVideo;
            for (Object dict : mediaDicts) {
                for (Class<?> current = dict.getClass();
                        current != null && current != Object.class;
                        current = current.getSuperclass()) {
                    Method[] methods;
                    try {
                        methods = current.getDeclaredMethods();
                    } catch (Throwable t) {
                        break;
                    }
                    for (Method method : methods) {
                        if (method.getParameterCount() != 0
                                || Modifier.isStatic(method.getModifiers())) {
                            continue;
                        }
                        Class<?> returnType = method.getReturnType();
                        boolean listAccessor = stillWantVideo && List.class.isAssignableFrom(returnType);
                        boolean userAccessor = wantUser && USER_CLASS.equals(returnType.getName());
                        if (!listAccessor && !userAccessor) {
                            continue;
                        }
                        Object result;
                        try {
                            method.setAccessible(true);
                            result = method.invoke(dict);
                        } catch (Throwable t) {
                            continue;
                        }
                        if (result == null) {
                            continue;
                        }
                        if (userAccessor) {
                            // Several of these exist (author, coauthors, tagged users). Collect
                            // them all; picking the actual owner happens in resolveUsername.
                            users.add(result);
                            continue;
                        }
                        if (!(result instanceof List)) {
                            continue;
                        }
                        List<?> list = (List<?>) result;
                        if (list.isEmpty()
                                || list.get(0) == null
                                || !implementsInterfaceNamed(list.get(0).getClass(), VIDEO_VERSION_INTERFACE)) {
                            continue;
                        }
                        for (Object element : list) {
                            if (element == null
                                    || !implementsInterfaceNamed(element.getClass(), VIDEO_VERSION_INTERFACE)) {
                                continue;
                            }
                            String url = asString(invokeNoArg(element, "getUrl"));
                            if (isMediaUrl(url)) {
                                addCandidate(new Candidate(url, true, videoResolutionWeight(element)));
                            }
                        }
                        if (hasVideo()) {
                            // Stop looking for videos, but keep going so the author accessors on
                            // this dict are still visited.
                            stillWantVideo = false;
                            if (!wantUser) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        /**
         * Picks the account the media belongs to, out of every user object the dict handed back.
         *
         * <p>A post can carry several: the author, coauthors, tagged users. They are indistinguishable
         * by accessor name, since those are obfuscated. The media id disambiguates them exactly:
         * Instagram formats it as {@code mediaPk_ownerPk}, and {@code User.getId()} is one of the
         * few unobfuscated methods, so the author is the user whose id equals that suffix.
         *
         * <p>When there is no media id to match against, fall back to the first user that yields
         * a plausible handle, which is the author for ordinary single author media.
         */
        private void resolveUsername() {
            if (username != null || users.isEmpty()) {
                return;
            }

            String ownerId = null;
            if (mediaId != null) {
                int separator = mediaId.indexOf('_');
                if (separator > 0 && separator < mediaId.length() - 1) {
                    ownerId = mediaId.substring(separator + 1);
                }
            }

            if (ownerId != null) {
                for (Object user : users) {
                    if (ownerId.equals(asString(invokeNoArg(user, "getId")))) {
                        String handle = handleOf(user);
                        if (handle != null) {
                            username = handle;
                            return;
                        }
                    }
                }
            }

            for (Object user : users) {
                String handle = handleOf(user);
                if (handle != null) {
                    username = handle;
                    return;
                }
            }
        }

        /** Reads the handle off a user object, via the accessor name the patch resolved. */
        private String handleOf(Object user) {
            String accessor = usernameAccessor;
            String handle = accessor != null ? asString(invokeNoArg(user, accessor)) : null;
            if (handle == null) {
                // Only reachable on a build where the patch could not resolve the accessor.
                handle = asString(invokeNoArg(user, "getUsername"));
            }
            return looksLikeUsername(handle) ? handle : null;
        }

        /** Opportunistically picks up a username and a media id for the saved file name. */
        private void harvestMetadata(Object value, Class<?> type) {
            if (username == null && declaresNoArg(type, "getUsername")) {
                String candidate = asString(invokeNoArg(value, "getUsername"));
                if (looksLikeUsername(candidate)) {
                    username = candidate;
                }
            }
            if (mediaId == null && declaresNoArg(type, "getId")) {
                String candidate = asString(invokeNoArg(value, "getId"));
                if (looksLikeMediaId(candidate)) {
                    mediaId = candidate;
                }
            }
        }

        private void addRawUrl(String value) {
            if (isMediaUrl(value)) {
                addCandidate(new Candidate(value, isVideoUrl(value), 0L));
            }
        }

        private void addCandidate(Candidate candidate) {
            if (!seenUrls.add(candidate.url)) {
                return;
            }
            if (candidate.video) {
                videos.add(candidate);
            } else {
                images.add(candidate);
            }
        }

        /**
         * Videos outrank images: a video post also carries a cover image, and the cover is
         * never what someone asking to save a video wants. Within a kind, larger wins.
         */
        List<Candidate> rank() {
            Comparator<Candidate> byWeight = new Comparator<Candidate>() {
                @Override
                public int compare(Candidate a, Candidate b) {
                    return Long.compare(b.weight, a.weight);
                }
            };
            Collections.sort(videos, byWeight);
            Collections.sort(images, byWeight);

            List<Candidate> all = new ArrayList<>(videos.size() + images.size());
            all.addAll(videos);
            all.addAll(images);
            return all;
        }
    }

    // endregion

    // region reflection helpers

    /**
     * Only Instagram and Facebook classes are worth walking into. Framework and library types
     * are dead ends and calling accessors on them risks real side effects.
     */
    private static boolean isAppClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("com.instagram.")
                || name.startsWith("com.facebook.")
                || name.startsWith("X.")
                || name.startsWith("LX.");
    }

    /**
     * The Pando/LiveTree-backed media dictionary, whose version lists are behind accessor methods
     * rather than fields. Matched on the interface-name fragment {@code "MediaDict"}, which covers
     * {@code MutableMediaDictIntf} and its relatives, with a fallback on the concrete
     * {@code LiveTreeMediaDict} class name.
     */
    private static boolean isMediaDict(Class<?> type) {
        if (implementsInterfaceNamed(type, "MediaDict")) {
            return true;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getName().endsWith(".LiveTreeMediaDict")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A resolution proxy for a {@code VideoVersionIntf}, so the highest quality variant wins.
     *
     * <p>Unlike {@code ImageUrl}, the video interface has no {@code getWidth}/{@code getHeight};
     * its width, height and a small type tag are three obfuscated {@code Integer} accessors whose
     * names change every release. Rather than resolve those names, this reads the value of every
     * zero-argument {@code Integer}-returning accessor and takes the product of the two largest.
     * Width and height are hundreds or thousands while the type tag is a small number, so the two
     * largest are always the dimensions, and their product is the pixel area. No accessor name is
     * assumed, so this survives obfuscation.
     *
     * <p>Returns 1 when no dimensions can be read, which still ranks a video above every image
     * (an image with a real area outranks 1, but videos are ranked before images regardless).
     */
    private static long videoResolutionWeight(Object video) {
        List<Integer> values = new ArrayList<>(4);
        for (Class<?> current = video.getClass();
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method method : methods) {
                if (method.getParameterCount() != 0
                        || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Integer.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(video);
                    if (value instanceof Integer && (Integer) value > 0) {
                        values.add((Integer) value);
                    }
                } catch (Throwable ignored) {
                    // Not a readable dimension; skip it.
                }
            }
        }
        if (values.isEmpty()) {
            return 1L;
        }
        Collections.sort(values, Collections.reverseOrder());
        long largest = values.get(0);
        long second = values.size() > 1 ? values.get(1) : 1L;
        return largest * second;
    }

    private static boolean implementsInterfaceNamed(Class<?> type, String simpleNameFragment) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getSimpleName().contains(simpleNameFragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean declaresNoArg(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Integer ? (Integer) value : null;
    }

    // endregion

    // region URL classification

    /** True for a URL that points at Instagram's own media CDN. */
    public static boolean isMediaUrl(String value) {
        if (value == null || value.length() < 12 || !value.startsWith("http")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("cdninstagram.") || lower.contains("fbcdn.net");
    }

    /** Classifies by the URL path, ignoring the signature query string CDN URLs carry. */
    public static boolean isVideoUrl(String value) {
        if (value == null) {
            return false;
        }
        int query = value.indexOf('?');
        String path = (query >= 0 ? value.substring(0, query) : value).toLowerCase(Locale.US);
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.contains("/v/t50.");
    }

    private static boolean looksLikeUsername(String value) {
        if (value == null || value.isEmpty() || value.length() > 30) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        // A bare number is a primary key, not a username.
        return !value.matches("\\d+");
    }

    /** Instagram media ids are "{mediaPk}_{ownerPk}". */
    private static boolean looksLikeMediaId(String value) {
        return value != null && value.matches("\\d{5,}_\\d{3,}");
    }

    // endregion
}

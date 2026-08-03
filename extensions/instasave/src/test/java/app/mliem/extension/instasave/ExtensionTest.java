package app.mliem.extension.instasave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.other.Outsider;
import com.instagram.test.Fakes;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Covers the logic that has no other safety net.
 *
 * <p>The graph walk is the part of this project most likely to be quietly wrong: it succeeds by
 * finding something plausible, so a subtly incorrect ranking or a missed nesting level looks
 * identical to working code right up until it saves the wrong image. None of it needs a device.
 */
public class ExtensionTest {

    private static final String CDN = "https://scontent-lhr8-1.cdninstagram.com/v/";

    private static Fakes.Media media(Object... candidates) {
        return new Fakes.Media("17912345678901234_1234567", Arrays.asList(candidates),
                new Fakes.User("someone"));
    }

    // region media resolution

    @Test
    public void picksTheLargestImageByPixelArea() {
        Fakes.Media media = media(
                new Fakes.Image(CDN + "small.jpg", 320, 320),
                new Fakes.Image(CDN + "huge.jpg", 1440, 1800),
                new Fakes.Image(CDN + "medium.jpg", 750, 750));

        MediaUrlResolver.Resolved resolved = MediaUrlResolver.resolve(media);

        assertNotNull(resolved);
        assertEquals(CDN + "huge.jpg", resolved.url);
        assertFalse(resolved.video);
    }

    @Test
    public void prefersVideoOverItsCoverImage() {
        // A video post carries a cover image, and the cover is never what someone asking to
        // save a video wants, no matter how large it is.
        Fakes.Media media = media(
                new Fakes.Image(CDN + "cover.jpg", 4000, 4000),
                new Fakes.Video(CDN + "clip.mp4", 720));

        MediaUrlResolver.Resolved resolved = MediaUrlResolver.resolve(media);

        assertNotNull(resolved);
        assertEquals(CDN + "clip.mp4", resolved.url);
        assertTrue(resolved.video);
    }

    @Test
    public void reachesMediaBuriedSeveralLevelsDown() {
        Object deep = new Fakes.Holder(new Fakes.Holder(new Fakes.Holder(
                media(new Fakes.Image(CDN + "deep.jpg", 640, 640)))));

        MediaUrlResolver.Resolved resolved = MediaUrlResolver.resolve(deep);

        assertNotNull(resolved);
        assertEquals(CDN + "deep.jpg", resolved.url);
    }

    @Test
    public void ignoresUrlsThatAreNotInstagramMedia() {
        Fakes.Media media = media("https://example.com/not-media.jpg", "hello", 42);

        assertNull(MediaUrlResolver.resolve(media));
    }

    @Test
    public void terminatesOnAReferenceCycle() {
        // Instagram's object graphs contain back references everywhere. Without the identity
        // based visited set this would not return.
        Fakes.Holder holder = new Fakes.Holder(media(new Fakes.Image(CDN + "a.jpg", 100, 100)));
        holder.self = holder;

        MediaUrlResolver.Resolved resolved = MediaUrlResolver.resolve(holder);

        assertNotNull(resolved);
        assertEquals(CDN + "a.jpg", resolved.url);
    }

    @Test
    public void returnsNullRatherThanGuessingWhenNothingIsFound() {
        assertNull(MediaUrlResolver.resolve(new Fakes.Holder(null)));
        assertNull(MediaUrlResolver.resolve(null));
    }

    @Test
    public void refusesToReadFieldsOfClassesOutsideInstagram() {
        // The gate is about field traversal, which is the part that makes a reflective sweep
        // dangerous: it calls setAccessible on whatever it meets. Strings and collections are
        // read wherever they turn up, but an arbitrary object's fields must stay unread even
        // when one of them holds a perfectly good CDN URL.
        assertNull(MediaUrlResolver.resolve(new Outsider(CDN + "unreachable.jpg")));
    }

    @Test
    public void stillReadsStringsAndCollectionsWhereverTheyAppear() {
        // The other half of that contract. A URL held in a plain list is fair game, which is
        // what the last resort raw scan depends on.
        List<Object> urls = new ArrayList<>();
        urls.add(CDN + "reachable.jpg");

        MediaUrlResolver.Resolved resolved = MediaUrlResolver.resolve(urls);

        assertNotNull(resolved);
        assertEquals(CDN + "reachable.jpg", resolved.url);
    }

    @Test
    public void carouselIndexSelectsTheRequestedSlide() {
        Fakes.Media media = media(
                new Fakes.Image(CDN + "slide0.jpg", 1000, 1000),
                new Fakes.Image(CDN + "slide1.jpg", 900, 900),
                new Fakes.Image(CDN + "slide2.jpg", 800, 800));

        assertEquals(CDN + "slide1.jpg", MediaUrlResolver.resolve(media, 1).url);
        assertEquals(CDN + "slide2.jpg", MediaUrlResolver.resolve(media, 2).url);
        // Out of range falls back to the best candidate rather than throwing.
        assertEquals(CDN + "slide0.jpg", MediaUrlResolver.resolve(media, 99).url);
    }

    @Test
    public void collectsUsernameAndMediaIdForTheFileName() {
        MediaUrlResolver.Resolved resolved =
                MediaUrlResolver.resolve(media(new Fakes.Image(CDN + "x.jpg", 10, 10)));

        assertNotNull(resolved);
        assertEquals("someone", resolved.username);
        assertEquals("17912345678901234_1234567", resolved.mediaId);
    }

    // endregion

    // region URL classification

    @Test
    public void classifiesVideoByPathIgnoringTheSignatureQueryString() {
        // CDN URLs always carry a signature query string, so classifying on the whole URL
        // would miss every real video.
        assertTrue(MediaUrlResolver.isVideoUrl(CDN + "clip.mp4?efg=abc&_nc_ht=x.jpg"));
        assertFalse(MediaUrlResolver.isVideoUrl(CDN + "photo.jpg?efg=abc"));
    }

    @Test
    public void recognisesBothInstagramCdnHosts() {
        assertTrue(MediaUrlResolver.isMediaUrl("https://scontent.cdninstagram.com/v/x.jpg"));
        assertTrue(MediaUrlResolver.isMediaUrl("https://scontent-lhr6-1.xx.fbcdn.net/v/x.jpg"));
        assertFalse(MediaUrlResolver.isMediaUrl("https://example.com/x.jpg"));
        assertFalse(MediaUrlResolver.isMediaUrl(null));
    }

    // endregion

    // region file naming

    @Test
    public void fileNameCarriesOwnerKindAndId() {
        String name = Downloader.buildFilename(
                MediaUrlResolver.resolve(media(new Fakes.Video(CDN + "v.mp4", 720))));

        assertTrue(name, name.startsWith("someone_video_17912345678901234_1234567_"));
        assertTrue(name, name.endsWith(".mp4"));
    }

    @Test
    public void fileNameStripsCharactersThatWouldBreakThePath() {
        MediaUrlResolver.Resolved hostile = MediaUrlResolver.resolve(
                new Fakes.Media("../../etc/passwd", Arrays.<Object>asList(
                        new Fakes.Image(CDN + "x.jpg", 10, 10)), new Fakes.User("someone")));

        String name = Downloader.buildFilename(hostile);

        assertFalse(name, name.contains("/"));
        assertFalse(name, name.contains(".."));
        assertTrue(name, name.endsWith(".jpg"));
    }

    // endregion

    // region flag overrides

    @Test
    public void overridesOnlyTheTwoDownloadFlags() {
        assertEquals(Boolean.TRUE, MobileConfigOverrides.evaluate(0x81035f00020d62L));
        assertEquals(Boolean.FALSE, MobileConfigOverrides.evaluate(0x81035f00030d63L));
    }

    @Test
    public void hasNoOpinionOnEveryOtherFlag() {
        // This is what makes hooking the shared read safe: it sits on a hot path where every
        // boolean flag in the app is answered, so anything but null here changes behaviour
        // far outside this feature.
        assertNull(MobileConfigOverrides.evaluate(0L));
        assertNull(MobileConfigOverrides.evaluate(-1L));
        assertNull(MobileConfigOverrides.evaluate(0x81035f00020d71L)); // the Instagram 436 id
        assertNull(MobileConfigOverrides.evaluate(0x81035f00020d63L)); // one bit away
    }

    // endregion
}

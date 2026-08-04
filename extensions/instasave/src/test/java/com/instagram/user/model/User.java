package com.instagram.user.model;

/**
 * Stands in for Instagram's media author model.
 *
 * <p>The fully qualified name matters and is part of what these tests cover: the resolver matches
 * this type by exact name, because several unrelated {@code User} classes ship in the app and only
 * this one is the media author.
 *
 * <p>It deliberately mirrors two awkward facts about the real class. There is no
 * {@code getUsername()}: no class in the app exposes one, which is why the handle has to be read
 * through an accessor whose obfuscated name the patch resolves and injects. And {@code getId()}
 * survives obfuscation, which is what lets the author be told apart from coauthors and tagged
 * users, since the media id is {@code mediaPk_ownerPk}.
 */
public final class User {

    private final String id;
    private final String handle;

    public User(String id, String handle) {
        this.id = id;
        this.handle = handle;
    }

    /** Unobfuscated in the real app, and the only reliable way to identify the author. */
    public String getId() {
        return id;
    }

    /**
     * The handle accessor. Named the way the real one is on Instagram 440, an obfuscated name
     * that no caller could guess, which is the whole reason the patch resolves it by a stable
     * field id and passes the name to the extension.
     */
    public String A05() {
        return handle;
    }
}

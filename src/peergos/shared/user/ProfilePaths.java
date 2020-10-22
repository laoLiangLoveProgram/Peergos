package peergos.shared.user;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** This class stores locations of the different components of a user's profile
 *
 *  Each component is a separate file and can thus be shared or made public individually.
 */
public class ProfilePaths {

    public static final Path ROOT = Paths.get(".profile");
    private static final Path PHOTO = ROOT.resolve("photo");
    public static final Path PHOTO_HIGH_RES = PHOTO.resolve("highres");
    public static final Path BIO = ROOT.resolve("bio");
    public static final Path STATUS = ROOT.resolve("status");
    public static final Path FIRSTNAME = ROOT.resolve("firstname");
    public static final Path LASTNAME = ROOT.resolve("lastname");
    public static final Path PHONE = ROOT.resolve("phone");
    public static final Path EMAIL = ROOT.resolve("email");
    public static final Path WEBROOT = ROOT.resolve("webroot"); // The path in Peergos to this users web root

    private static <V> CompletableFuture<Optional<V>> getAndParse(Path p, Function<byte[], V> parser, UserContext viewer) {
        return viewer.getByPath(p)
                .thenCompose(opt -> opt.map(f -> Serialize.readFully(f, viewer.crypto, viewer.network)
                        .thenApply(parser)
                        .thenApply(Optional::of))
                        .orElse(Futures.of(Optional.empty())));
    }

    private static <V> CompletableFuture<Boolean> serializeAndSet(Path p, V val, Function<V, byte[]> serialize, UserContext user) {
        byte[] raw = serialize.apply(val);
        return user.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(p.getParent(), user.network, true, user.crypto))
                .thenCompose(parent -> parent.uploadOrReplaceFile(p.getFileName().toString(),
                        AsyncReader.build(raw), raw.length, user.network, user.crypto, x -> {},
                        user.crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH)))
                .thenApply(x -> true);
    }

    public static CompletableFuture<Optional<byte[]>> getProfilePhoto(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(PHOTO_HIGH_RES), x -> x, viewer);
    }

    public static CompletableFuture<Boolean> setProfilePhoto(UserContext user, byte[] image) {
        return serializeAndSet(PHOTO_HIGH_RES, image, x -> x, user);
    }

    public static CompletableFuture<Optional<String>> getBio(String user, UserContext viewer) {
        return getAndParse(Paths.get(user).resolve(BIO), String::new, viewer);
    }

    public static CompletableFuture<Boolean> setBio(UserContext user, String bio) {
        return serializeAndSet(BIO, bio, String::getBytes, user);
    }
}

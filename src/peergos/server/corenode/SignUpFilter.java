package peergos.server.corenode;

import peergos.server.storage.admin.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class SignUpFilter implements CoreNode {

    private final CoreNode target;
    private final QuotaAdmin judge;
    private final Multihash ourNodeId;
    private final QuotaAdmin quotaStore;
    private final HttpSpaceUsage space;


    public SignUpFilter(CoreNode target,
                        QuotaAdmin judge,
                        Multihash ourNodeId,
                        QuotaAdmin quotaStore,
                        HttpSpaceUsage space) {
        this.target = target;
        this.judge = judge;
        this.ourNodeId = ourNodeId;
        this.quotaStore = quotaStore;
        this.space = space;
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username, List<UserPublicKeyLink> chain, ProofOfWork proof) {
        if (! forUs(chain))
            return target.updateChain(username, chain, proof);
        return CompletableFuture.completedFuture(true)
                .thenCompose(x -> {
                    if (judge.allowSignupOrUpdate(username))
                        return target.updateChain(username, chain, proof);
                    throw new IllegalStateException("This server is not currently accepting new sign ups. Please try again later");
                });
    }

    private boolean forUs(List<UserPublicKeyLink> chain) {
        return chain.get(chain.size() - 1).claim.storageProviders.contains(ourNodeId);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return target.getUsername(key);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId) {
        if (forUs(newChain)) {
            if (! judge.allowSignupOrUpdate(username))
                throw new IllegalStateException("This server is not currently accepting new user migrations.");
            PublicKeyHash owner = newChain.get(newChain.size() - 1).owner;

            // check we have enough local quota to mirror all user's data
            long currentUsage = space.getUsage(currentStorageId, owner).join();
            long localQuota = quotaStore.getQuota(username);
            if (localQuota < currentUsage)
                throw new IllegalStateException("Not enough space for user to migrate user to this server!");
        }

        return target.migrateUser(username, newChain, currentStorageId);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}

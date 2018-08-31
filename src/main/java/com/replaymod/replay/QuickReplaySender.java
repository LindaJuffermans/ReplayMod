//#if MC>=10904
package com.replaymod.replay;

import com.github.steveice10.mc.protocol.data.game.PlayerListEntry;
import com.github.steveice10.mc.protocol.data.game.PlayerListEntryAction;
import com.github.steveice10.mc.protocol.data.game.chunk.BlockStorage;
import com.github.steveice10.mc.protocol.data.game.chunk.Column;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.Position;
import com.github.steveice10.mc.protocol.data.game.entity.player.GameMode;
import com.github.steveice10.mc.protocol.data.game.setting.Difficulty;
import com.github.steveice10.mc.protocol.data.game.world.WorldType;
import com.github.steveice10.mc.protocol.data.game.world.block.BlockChangeRecord;
import com.github.steveice10.mc.protocol.data.game.world.block.BlockState;
import com.github.steveice10.mc.protocol.data.game.world.notify.ClientNotification;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerPlayerListEntryPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerRespawnPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityDestroyPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityTeleportPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerPositionRotationPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnMobPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnObjectPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnPaintingPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnPlayerPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerBlockChangePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerChunkDataPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerMultiBlockChangePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerNotifyClientPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerUnloadChunkPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerUpdateTimePacket;
import com.github.steveice10.mc.protocol.util.NetUtil;
import com.github.steveice10.packetlib.io.NetInput;
import com.github.steveice10.packetlib.io.NetOutput;
import com.github.steveice10.packetlib.io.stream.StreamNetInput;
import com.github.steveice10.packetlib.io.stream.StreamNetOutput;
import com.google.common.base.Optional;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.replaymod.core.utils.Utils;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.io.ReplayOutputStream;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import com.replaymod.replaystudio.util.Location;
import com.replaymod.replaystudio.util.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

//#if MC>=11200
import com.replaymod.core.utils.WrappedTimer;
//#endif

import static com.replaymod.core.versions.MCVer.FML_BUS;
import static com.replaymod.replay.ReplayModReplay.LOGGER;

/**
 * Sends only chunk updates and entity position updates but tries to do so as quickly as possible.
 * To do so, it performs an initial analysis of the replay, scanning all of its packets and storing entity positions
 * and chunk states while doing so.
 * This allows it to later jump to any time by doing a diff from the current time (including backwards jumping).
 */
@ChannelHandler.Sharable
public class QuickReplaySender extends ChannelHandlerAdapter implements ReplaySender {
    private static final String CACHE_ENTRY = "quickModeCache.bin";
    private static final int CACHE_VERSION = 0;

    private final Minecraft mc = Minecraft.getMinecraft();

    private final ReplayModReplay mod;
    private final ReplayFile replayFile;
    private ChannelHandlerContext ctx;

    private int currentTimeStamp;
    private double replaySpeed = 1;

    /**
     * Whether async mode is enabled.
     * Async mode is emulated by registering an event handler on client tick.
     */
    private boolean asyncMode;
    private long lastAsyncUpdateTime;

    private ListenableFuture<Void> initPromise;

    private TreeMap<Integer, Collection<BakedTrackedThing>> thingSpawnsT = new TreeMap<>();
    private ListMultimap<Integer, BakedTrackedThing> thingSpawns = Multimaps.newListMultimap(thingSpawnsT, ArrayList::new);
    private TreeMap<Integer, Collection<BakedTrackedThing>> thingDespawnsT = new TreeMap<>();
    private ListMultimap<Integer, BakedTrackedThing> thingDespawns = Multimaps.newListMultimap(thingDespawnsT, ArrayList::new);
    private List<BakedTrackedThing> activeThings = new LinkedList<>();
    private TreeMap<Integer, Packet<?>> worldTimes = new TreeMap<>();
    private TreeMap<Integer, Packet<?>> thunderStrengths = new TreeMap<>(); // For some reason, this isn't tied to Weather

    public QuickReplaySender(ReplayModReplay mod, ReplayFile replayFile) {
        this.mod = mod;
        this.replayFile = replayFile;
    }

    public void register() {
        FML_BUS.register(this);
    }

    public void unregister() {
        FML_BUS.unregister(this);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    public ListenableFuture<Void> getInitializationPromise() {
        return initPromise;
    }

    public ListenableFuture<Void> initialize(Consumer<Double> progress) {
        if (initPromise != null) {
            return initPromise;
        }
        SettableFuture<Void> promise = SettableFuture.create();
        initPromise = promise;
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                if (!tryLoadFromCache(progress)) {
                    double progressSplit = 0.9; // 90% of progress time for analysing, 10% for loading
                    analyseReplay(replayFile, d -> progress.accept(d * progressSplit));
                    tryLoadFromCache(d -> progress.accept(d * (1 - progressSplit) + progressSplit));
                }
                LOGGER.info("Initialized quick replay sender in " + (System.currentTimeMillis() - start) + "ms");
            } catch (Throwable e) {
                LOGGER.error("Initializing quick replay sender:", e);
                mod.getCore().runLater(() -> {
                    mod.getCore().printWarningToChat("Error initializing quick replay sender: %s", e.getLocalizedMessage());
                    promise.setException(e);
                });
                return;
            }
            mod.getCore().runLater(() -> promise.set(null));
        }).start();
        return promise;
    }

    private void ensureInitialized(Runnable body) {
        if (initPromise == null) {
            LOGGER.warn("QuickReplaySender used without prior initialization!", new Throwable());
            initialize(progress -> {});
        }
        Futures.addCallback(initPromise, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                body.run();
            }

            @Override
            public void onFailure(Throwable t) {
                // Error already printed by initialize method
            }
        });
    }

    public void restart() {
        activeThings.clear();
        currentTimeStamp = 0;
        ctx.fireChannelRead(toMC(new ServerRespawnPacket(0, Difficulty.NORMAL, GameMode.SPECTATOR, WorldType.DEFAULT)));
        ctx.fireChannelRead(toMC(new ServerPlayerPositionRotationPacket(0, 0, 0, 0, 0, 0)));
    }

    @Override
    public int currentTimeStamp() {
        return currentTimeStamp;
    }

    @Override
    public void setReplaySpeed(double factor) {
        if (factor != 0) {
            if (paused() && asyncMode) {
                lastAsyncUpdateTime = System.currentTimeMillis(); // TODO test this
            }
            this.replaySpeed = factor;
        }
        //#if MC>=11200
        mc.timer.tickLength = WrappedTimer.DEFAULT_MS_PER_TICK / (float) factor;
        //#else
        //$$ mc.timer.timerSpeed = (float) factor;
        //#endif
    }

    @Override
    public double getReplaySpeed() {
        return replaySpeed;
    }

    @Override
    public boolean isAsyncMode() {
        return asyncMode;
    }

    @Override
    public void setAsyncMode(boolean async) {
        if (this.asyncMode == async) return;
        ensureInitialized(() -> {
            this.asyncMode = async;
            if (async) {
                lastAsyncUpdateTime = System.currentTimeMillis();
            }
        });
    }

    @Override
    public void setSyncModeAndWait() {
        setAsyncMode(false);
        // No waiting required, we emulated async mode via tick events
    }

    @Override
    public void jumpToTime(int value) {
        sendPacketsTill(value);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!asyncMode) return;

        long now = System.currentTimeMillis();
        long realTimePassed = now - lastAsyncUpdateTime;
        lastAsyncUpdateTime = now;
        int replayTimePassed = (int) (realTimePassed * replaySpeed);
        sendPacketsTill(currentTimeStamp + replayTimePassed);
    }

    private boolean tryLoadFromCache(Consumer<Double> progress) throws IOException {
        Optional<InputStream> cacheOpt = replayFile.get(CACHE_ENTRY);
        if (!cacheOpt.isPresent()) return false;
        boolean success = loadFromCache(cacheOpt.get(), progress);
        if (!success) {
            thingSpawnsT.clear();
            thingDespawnsT.clear();
            worldTimes.clear();
            thunderStrengths.clear();
        }
        return success;
    }

    private boolean loadFromCache(InputStream rawIn, Consumer<Double> progress) throws IOException {
        long sysTimeStart = System.currentTimeMillis();
        NetInput in = new StreamNetInput(rawIn);
        int version = in.readVarInt();
        if (version > CACHE_VERSION) return false; // More recent than this version, no chance we can read that

        int size = in.readVarInt();
        int spawnTime = 0;
        for (int i = 0; i < size; i++) {
            progress.accept((double) i / size);
            spawnTime += in.readVarInt();
            for (int j = in.readVarInt(); j > 0; j--) {
                int despawnTime = in.readVarInt();
                BakedTrackedThing trackedThing;
                switch (in.readVarInt()) {
                    case 0: trackedThing = new BakedEntity(version, in); break;
                    case 1: trackedThing = new BakedChunk(version, in); break;
                    case 2: trackedThing = new BakedWeather(version, in); break;
                    default: return false;
                }
                trackedThing.spawnTime = spawnTime;
                trackedThing.despawnTime = despawnTime;
                thingSpawns.put(spawnTime, trackedThing);
                thingDespawns.put(despawnTime, trackedThing);
            }
        }

        // These should go quick, they probably won't need to update progress
        progress.accept(1.0);
        readFromCache(version, in, worldTimes);
        readFromCache(version, in, thunderStrengths);

        LOGGER.info("Loaded quick replay from cache in " + (System.currentTimeMillis() - sysTimeStart) + "ms");
        return true;
    }

    private static void analyseReplay(ReplayFile replayFile, Consumer<Double> progress) throws IOException {
        ReplayStudio studio = new ReplayStudio();
        PacketUtils.registerAllMovementRelated(studio);
        studio.setParsing(ServerSpawnMobPacket.class, true);
        studio.setParsing(ServerSpawnObjectPacket.class, true);
        studio.setParsing(ServerSpawnPaintingPacket.class, true);
        studio.setParsing(ServerSpawnPlayerPacket.class, true);
        studio.setParsing(ServerEntityDestroyPacket.class, true);
        studio.setParsing(ServerChunkDataPacket.class, true);
        studio.setParsing(ServerUnloadChunkPacket.class, true);
        studio.setParsing(ServerBlockChangePacket.class, true);
        studio.setParsing(ServerMultiBlockChangePacket.class, true);
        studio.setParsing(ServerPlayerListEntryPacket.class, true);
        studio.setParsing(ServerUpdateTimePacket.class, true);
        studio.setParsing(ServerNotifyClientPacket.class, true);

        TreeMap<Integer, Collection<TrackedThing>> thingSpawnsT = new TreeMap<>();
        ListMultimap<Integer, TrackedThing> thingSpawns = Multimaps.newListMultimap(thingSpawnsT, ArrayList::new);
        TreeMap<Integer, com.github.steveice10.packetlib.packet.Packet> worldTimes = new TreeMap<>();
        TreeMap<Integer, com.github.steveice10.packetlib.packet.Packet> thunderStrengths = new TreeMap<>();
        Map<UUID, PlayerListEntry> playerListEntries = new HashMap<>();
        Map<Integer, Entity> activeEntities = new HashMap<>();
        Map<Long, Chunk> activeChunks = new HashMap<>();
        Weather activeWeather = null;

        // 90% of estimated time are for analysis, 10% for writing cache
        // This heuristic is based on simply running the function on some 3h replay.
        // Numbers may be significantly different for smaller replays where GC doesn't kick in. But the progress bar
        // will hopefully be barely visible in that case anyway.
        // May need to be updated when performance of MCProtocolLib or this code is improved.
        // Same for the split between analyseReplay and loadFromCache declared in #initialize(...)
        final double progressSplit = 0.9;

        double sysTimeStart = System.currentTimeMillis();
        double duration;
        try (ReplayInputStream in = replayFile.getPacketData(studio)) {
            duration = replayFile.getMetaData().getDuration();
            PacketData packetData;
            while ((packetData = in.readPacket()) != null) {
                com.github.steveice10.packetlib.packet.Packet packet = packetData.getPacket();
                int time = (int) packetData.getTime();
                progress.accept(time / duration * progressSplit);
                Integer entityId = PacketUtils.getEntityId(packet);
                if (packet instanceof ServerSpawnMobPacket
                        || packet instanceof ServerSpawnObjectPacket
                        || packet instanceof ServerSpawnPaintingPacket) {
                    Entity entity = new Entity(entityId, Collections.singletonList(packet));
                    entity.spawnTime = time;
                    thingSpawns.put(time, entity);
                    Entity prev = activeEntities.put(entityId, entity);
                    if (prev != null) {
                        prev.despawnTime = time;
                    }
                } else if (packet instanceof ServerSpawnPlayerPacket) {
                    ServerPlayerListEntryPacket listEntryPacket = new ServerPlayerListEntryPacket(
                            PlayerListEntryAction.ADD_PLAYER,
                            new PlayerListEntry[]{
                                    playerListEntries.get(((ServerSpawnPlayerPacket) packet).getUUID())
                            }
                    );
                    Entity entity = new Entity(entityId, Arrays.asList(listEntryPacket, packet));
                    entity.spawnTime = time;
                    thingSpawns.put(time, entity);
                    Entity prev = activeEntities.put(entityId, entity);
                    if (prev != null) {
                        prev.despawnTime = time;
                    }
                } else if (packet instanceof ServerEntityDestroyPacket) {
                    for (int id : ((ServerEntityDestroyPacket) packet).getEntityIds()) {
                        Entity entity = activeEntities.remove(id);
                        if (entity != null) {
                            entity.despawnTime = time;
                        }
                    }
                } else if (packet instanceof ServerChunkDataPacket) {
                    Column column = ((ServerChunkDataPacket) packet).getColumn();
                    Chunk chunk = new Chunk(column);
                    chunk.spawnTime = time;
                    thingSpawns.put(time, chunk);
                    Chunk prev = activeChunks.put(coordToLong(column.getX(), column.getZ()), chunk);
                    if (prev != null) {
                        prev.currentBlockState = null; // free memory because we no longer need it
                        prev.despawnTime = time;
                    }
                } else if (packet instanceof ServerUnloadChunkPacket) {
                    ServerUnloadChunkPacket p = (ServerUnloadChunkPacket) packet;
                    Chunk prev = activeChunks.remove(coordToLong(p.getX(), p.getZ()));
                    if (prev != null) {
                        prev.currentBlockState = null; // free memory because we no longer need it
                        prev.despawnTime = time;
                    }
                } else if (packet instanceof ServerBlockChangePacket || packet instanceof ServerMultiBlockChangePacket) {
                    for (BlockChangeRecord record :
                            packet instanceof ServerBlockChangePacket
                                    ? new BlockChangeRecord[]{ ((ServerBlockChangePacket) packet).getRecord() }
                                    : ((ServerMultiBlockChangePacket) packet).getRecords()) {
                        Position pos = record.getPosition();
                        Chunk chunk = activeChunks.get(coordToLong(pos.getX() / 16, pos.getZ() / 16));
                        if (chunk != null) {
                            BlockStorage blockStorage = chunk.currentBlockState[pos.getY() / 16];
                            int x = Math.floorMod(pos.getX(), 16), y = Math.floorMod(pos.getY(), 16), z = Math.floorMod(pos.getZ(), 16);
                            BlockState prevState = blockStorage.get(x, y, z);
                            BlockState newState = record.getBlock();
                            blockStorage.set(x, y, z, newState);
                            chunk.blocks.put(time, new BlockChange(pos, prevState, newState));
                        }
                    }
                } else if (packet instanceof ServerPlayerListEntryPacket) {
                    ServerPlayerListEntryPacket p = (ServerPlayerListEntryPacket) packet;
                    if (p.getAction() == PlayerListEntryAction.ADD_PLAYER) {
                        for (PlayerListEntry entry : p.getEntries()) {
                            playerListEntries.put(entry.getProfile().getId(), entry);
                        }
                    }
                } else if (packet instanceof ServerRespawnPacket) {
                    activeEntities.values().forEach(entity -> entity.despawnTime = time);
                    activeEntities.clear();
                    activeChunks.values().forEach(chunk -> chunk.despawnTime = time);
                    activeChunks.clear();
                    if (activeWeather != null) {
                        activeWeather.despawnTime = time;
                    }
                    activeWeather = null;
                } else if (packet instanceof ServerUpdateTimePacket) {
                    worldTimes.put(time, packet);
                } else if (packet instanceof ServerNotifyClientPacket) {
                    ServerNotifyClientPacket p = (ServerNotifyClientPacket) packet;
                    switch (p.getNotification()) {
                        case START_RAIN:
                            if (activeWeather != null) {
                                activeWeather.despawnTime = time;
                            }
                            activeWeather = new Weather();
                            activeWeather.spawnTime = time;
                            thingSpawns.put(time, activeWeather);
                            break;
                        case STOP_RAIN:
                            if (activeWeather != null) {
                                activeWeather.despawnTime = time;
                                activeWeather = null;
                            }
                            break;
                        case RAIN_STRENGTH:
                            if (activeWeather != null) {
                                activeWeather.rainStrengths.put(time, packet);
                            }
                            break;
                        case THUNDER_STRENGTH:
                            thunderStrengths.put(time, packet);
                            break;
                        default: break;
                    }
                }
                if (entityId != null) {
                    Entity entity = activeEntities.get(entityId);
                    if (entity != null) {
                        Location current = entity.locations.isEmpty() ? null : entity.locations.lastEntry().getValue();
                        Location updated = PacketUtils.updateLocation(current, packet);
                        if (updated != null) {
                            entity.locations.put(time, updated);
                        }
                    }
                }
            }
        }
        LOGGER.info("Analysed replay in " + (System.currentTimeMillis() - sysTimeStart) + "ms");

        sysTimeStart = System.currentTimeMillis();
        try (OutputStream cacheOut = replayFile.write(CACHE_ENTRY)) {
            NetOutput out = new StreamNetOutput(cacheOut);
            out.writeVarInt(CACHE_VERSION);

            out.writeVarInt(thingSpawnsT.size());
            int lastTime = 0;
            for (Map.Entry<Integer, Collection<TrackedThing>> entry : thingSpawnsT.entrySet()) {
                int time = entry.getKey();
                out.writeVarInt(time - lastTime);
                lastTime = time;

                progress.accept(time / duration * (1 - progressSplit) + progressSplit);

                Collection<TrackedThing> trackedThings = entry.getValue();
                out.writeVarInt(trackedThings.size());
                for (TrackedThing trackedThing : trackedThings) {
                    out.writeVarInt(trackedThing.despawnTime);
                    if (trackedThing instanceof Entity) {
                        out.writeVarInt(0);
                    } else if (trackedThing instanceof Chunk) {
                        out.writeVarInt(1);
                    } else if (trackedThing instanceof Weather) {
                        out.writeVarInt(2);
                    } else {
                        throw new UnsupportedOperationException("Unknown type of tracked thing: " + trackedThing);
                    }
                    trackedThing.writeToCache(out);
                }
            }
            writeToCache(out, worldTimes);
            writeToCache(out, thunderStrengths);
        }
        LOGGER.info("Wrote quick replay to cache in " + (System.currentTimeMillis() - sysTimeStart) + "ms");
    }

    @Override
    public void sendPacketsTill(int replayTime) {
        ensureInitialized(() -> {
            if (replayTime > currentTimeStamp) {
                activeThings.removeIf(thing -> {
                    if (thing.despawnTime < replayTime) {
                        thing.despawnPackets.forEach(ctx::fireChannelRead);
                        return true;
                    } else {
                        return false;
                    }
                });
                thingSpawnsT.subMap(currentTimeStamp, false, replayTime, true).values()
                        .forEach(things -> things.forEach(thing -> {
                            if (thing.despawnTime > replayTime) {
                                thing.spawnPackets.forEach(ctx::fireChannelRead);
                                activeThings.add(thing);
                            }
                        }));
                activeThings.forEach(thing -> thing.play(currentTimeStamp, replayTime, ctx::fireChannelRead));
                playMap(worldTimes, currentTimeStamp, replayTime, ctx::fireChannelRead);
                playMap(thunderStrengths, currentTimeStamp, replayTime, ctx::fireChannelRead);
            } else {
                activeThings.removeIf(thing -> {
                    if (thing.spawnTime > replayTime) {
                        thing.despawnPackets.forEach(ctx::fireChannelRead);
                        return true;
                    } else {
                        return false;
                    }
                });
                thingDespawnsT.subMap(replayTime, false, currentTimeStamp, true).values()
                        .forEach(things -> things.forEach(thing -> {
                            if (thing.spawnTime <= replayTime) {
                                thing.spawnPackets.forEach(ctx::fireChannelRead);
                                activeThings.add(thing);
                            }
                        }));
                activeThings.forEach(thing -> thing.rewind(currentTimeStamp, replayTime, ctx::fireChannelRead));
                rewindMap(worldTimes, currentTimeStamp, replayTime, ctx::fireChannelRead);
                rewindMap(thunderStrengths, currentTimeStamp, replayTime, ctx::fireChannelRead);
            }
            currentTimeStamp = replayTime;
        });
    }

    private static final ByteBuf byteBuf = Unpooled.buffer();
    private static final ByteBufOutputStream byteBufOut = new ByteBufOutputStream(byteBuf);
    private static final PacketBuffer packetBuf = new PacketBuffer(byteBuf);
    private static final ReplayOutputStream encoder = new ReplayOutputStream(new ReplayStudio(), byteBufOut);

    private static Packet<?> toMC(com.github.steveice10.packetlib.packet.Packet packet) {
        // We need to re-encode MCProtocolLib packets, so we can then decode them as NMS packets
        // The main reason we aren't reading them as NMS packets is that we want ReplayStudio to be able
        // to apply ViaVersion (and potentially other magic) to it.
        synchronized (encoder) {
            int readerIndex = byteBuf.readerIndex(); // Mark the current reader and writer index (should be at start)
            int writerIndex = byteBuf.writerIndex();
            try {
                encoder.write(0, packet); // Re-encode packet, data will end up in byteBuf
                encoder.flush();

                byteBuf.skipBytes(8); // Skip packet length & timestamp

                int packetId =
                        //#if MC>=11102
                        packetBuf.readVarInt();
                        //#else
                        //$$ packetBuf.readVarIntFromBuffer();
                        //#endif
                Packet<?> mcPacket = EnumConnectionState.PLAY.getPacket(EnumPacketDirection.CLIENTBOUND, packetId);
                mcPacket.readPacketData(packetBuf);
                return mcPacket;
            } catch (Exception e) {
                Utils.throwIfUnchecked(e);
                throw new RuntimeException(e);
            } finally {
                byteBuf.readerIndex(readerIndex); // Reset reader & writer index for next use
                byteBuf.writerIndex(writerIndex);
            }
        }
    }

    private static Packet<?> readPacketFromCache(int version, NetInput in) throws IOException {
        int readerIndex = byteBuf.readerIndex(); // Mark the current reader and writer index (should be at start)
        int writerIndex = byteBuf.writerIndex();
        try {
            packetBuf.writeBytes(in.readBytes(in.readVarInt()));

            int packetId =
                    //#if MC>=11102
                    packetBuf.readVarInt();
                    //#else
                    //$$ packetBuf.readVarIntFromBuffer();
                    //#endif
            Packet<?> mcPacket = EnumConnectionState.PLAY.getPacket(EnumPacketDirection.CLIENTBOUND, packetId);
            mcPacket.readPacketData(packetBuf);
            return mcPacket;
        } catch (Exception e) {
            Utils.throwIfInstanceOf(e, IOException.class);
            Utils.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            byteBuf.readerIndex(readerIndex); // Reset reader & writer index for next use
            byteBuf.writerIndex(writerIndex);
        }
    }

    private static List<Packet<?>> readPacketsFromCache(int version, NetInput in) throws IOException {
        int size = in.readVarInt();
        List<Packet<?>> packets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            packets.add(readPacketFromCache(version, in));
        }
        return packets;
    }

    private static void readFromCache(int version, NetInput in, SortedMap<Integer, Packet<?>> packets) throws IOException {
        int time = 0;
        for (int i = in.readVarInt(); i > 0; i--) {
            time += in.readVarInt();
            packets.put(time, readPacketFromCache(version, in));
        }
    }

    private static void writeToCache(NetOutput out, com.github.steveice10.packetlib.packet.Packet packet) throws IOException {
        int readerIndex = byteBuf.readerIndex(); // Mark the current reader and writer index (should be at start)
        int writerIndex = byteBuf.writerIndex();
        try {
            encoder.write(0, packet); // Re-encode packet, data will end up in byteBuf
            encoder.flush();

            byteBuf.skipBytes(8); // Skip packet length & timestamp

            int size = byteBuf.readableBytes();
            out.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                out.writeByte(byteBuf.readByte());
            }
        } catch (Exception e) {
            Utils.throwIfInstanceOf(e, IOException.class);
            Utils.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            byteBuf.readerIndex(readerIndex); // Reset reader & writer index for next use
            byteBuf.writerIndex(writerIndex);
        }
    }

    private static void writeToCache(NetOutput out, Collection<com.github.steveice10.packetlib.packet.Packet> packets) throws IOException {
        out.writeVarInt(packets.size());
        for (com.github.steveice10.packetlib.packet.Packet packet : packets) {
            writeToCache(out, packet);
        }
    }

    private static void writeToCache(NetOutput out, SortedMap<Integer, com.github.steveice10.packetlib.packet.Packet> packets) throws IOException {
        out.writeVarInt(packets.size());
        int lastTime = 0;
        for (Map.Entry<Integer, com.github.steveice10.packetlib.packet.Packet> entry : packets.entrySet()) {
            int time = entry.getKey();
            out.writeVarInt(time - lastTime);
            lastTime = time;

            writeToCache(out, entry.getValue());
        }
    }

    private static long coordToLong(int x, int z) {
        return (long)x << 32 | (long)z & 0xFFFFFFFFL;
    }

    private static <V> void playMap(NavigableMap<Integer, V> updates, int currentTimeStamp, int replayTime, Consumer<V> update) {
        Map.Entry<Integer, V> lastUpdate = updates.floorEntry(replayTime);
        if (lastUpdate != null && lastUpdate.getKey() > currentTimeStamp) {
            update.accept(lastUpdate.getValue());
        }
    }

    private static <V> void rewindMap(NavigableMap<Integer, V> updates, int currentTimeStamp, int replayTime, Consumer<V> update) {
        Map.Entry<Integer, V> lastUpdate = updates.floorEntry(replayTime);
        if (lastUpdate != null && !lastUpdate.getKey().equals(updates.floorKey(currentTimeStamp))) {
            update.accept(lastUpdate.getValue());
        }
    }

    private static abstract class TrackedThing {
        List<com.github.steveice10.packetlib.packet.Packet> spawnPackets;
        List<com.github.steveice10.packetlib.packet.Packet> despawnPackets;
        int spawnTime;
        int despawnTime = Integer.MAX_VALUE;

        private TrackedThing(List<com.github.steveice10.packetlib.packet.Packet> spawnPackets,
                             List<com.github.steveice10.packetlib.packet.Packet> despawnPackets) {
            this.spawnPackets = spawnPackets;
            this.despawnPackets = despawnPackets;
        }

        public void writeToCache(NetOutput out) throws IOException {
            QuickReplaySender.writeToCache(out, spawnPackets);
            QuickReplaySender.writeToCache(out, despawnPackets);
        }
    }

    // For quicker jumping we store MC packets.
    // However, during replay analysis it's easier to use MCProtocolLib ones in part because MC packets have been
    // renamed from 1.8 to 1.9 but also because we cannot easily serialize them for caching.
    // Therefore during analysis we use TrackedThing which we then serialize (we'd have to do that anyway for caching)
    // and afterwards unserialize in BakedTrackedThing as MC packets for replaying.
    private static abstract class BakedTrackedThing {
        List<Packet<?>> spawnPackets;
        List<Packet<?>> despawnPackets;
        int spawnTime;
        int despawnTime = Integer.MAX_VALUE;

        private BakedTrackedThing(int version, NetInput in) throws IOException {
            spawnPackets = readPacketsFromCache(version, in);
            despawnPackets = readPacketsFromCache(version, in);
        }

        public abstract void play(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send);
        public abstract void rewind(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send);
    }

    private static class Entity extends TrackedThing {
        private int id;
        private NavigableMap<Integer, Location> locations = new TreeMap<>();

        private Entity(int entityId, List<com.github.steveice10.packetlib.packet.Packet> spawnPackets) {
            super(spawnPackets, Collections.singletonList(new ServerEntityDestroyPacket(entityId)));
            this.id = entityId;
        }

        @Override
        public void writeToCache(NetOutput out) throws IOException {
            super.writeToCache(out);

            out.writeVarInt(id);
            out.writeVarInt(locations.size());
            int lastTime = 0;
            for (Map.Entry<Integer, Location> entry : locations.entrySet()) {
                int time = entry.getKey();
                Location loc = entry.getValue();
                out.writeVarInt(time - lastTime);
                lastTime = time;
                out.writeDouble(loc.getX());
                out.writeDouble(loc.getY());
                out.writeDouble(loc.getZ());
                out.writeFloat(loc.getYaw());
                out.writeFloat(loc.getPitch());
            }
        }
    }

    private static class BakedEntity extends BakedTrackedThing {
        private int id;
        private NavigableMap<Integer, Location> locations = new TreeMap<>();

        private BakedEntity(int version, NetInput in) throws IOException {
            super(version, in);

            id = in.readVarInt();
            int time = 0;
            for (int i = in.readVarInt(); i > 0; i--) {
                time += in.readVarInt();
                locations.put(time, new Location(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat()));
            }
        }

        @Override
        public void play(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            playMap(locations, currentTimeStamp, replayTime, l ->
                    send.accept(toMC(new ServerEntityTeleportPacket(id, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), false))));
        }

        @Override
        public void rewind(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            rewindMap(locations, currentTimeStamp, replayTime, l ->
                    send.accept(toMC(new ServerEntityTeleportPacket(id, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), false))));
        }
    }

    private static class Chunk extends TrackedThing {
        private TreeMap<Integer, Collection<BlockChange>> blocksT = new TreeMap<>();
        private ListMultimap<Integer, BlockChange> blocks = Multimaps.newListMultimap(blocksT, LinkedList::new); // LinkedList to allow .descendingIterator
        private BlockStorage[] currentBlockState = new BlockStorage[16];

        private Chunk(Column column) {
            super(Collections.singletonList(new ServerChunkDataPacket(column)),
                    Collections.singletonList(new ServerUnloadChunkPacket(column.getX(), column.getZ())));
            com.github.steveice10.mc.protocol.data.game.chunk.Chunk[] chunks = column.getChunks();
            for (int i = 0; i < currentBlockState.length; i++) {
                currentBlockState[i] = chunks[i] == null ? new BlockStorage() : chunks[i].getBlocks();
            }
        }

        @Override
        public void writeToCache(NetOutput out) throws IOException {
            super.writeToCache(out);

            out.writeVarInt(blocksT.size());
            int lastTime = 0;
            for (Map.Entry<Integer, Collection<BlockChange>> entry : blocksT.entrySet()) {
                int time = entry.getKey();
                out.writeVarInt(time - lastTime);
                lastTime = time;

                Collection<BlockChange> blockChanges = entry.getValue();
                out.writeVarInt(blockChanges.size());
                for (BlockChange blockChange : blockChanges) {
                    NetUtil.writePosition(out, blockChange.pos);
                    NetUtil.writeBlockState(out, blockChange.from);
                    NetUtil.writeBlockState(out, blockChange.to);
                }
            }
        }
    }

    private static class BakedChunk extends BakedTrackedThing {
        private TreeMap<Integer, Collection<BlockChange>> blocksT = new TreeMap<>();
        private ListMultimap<Integer, BlockChange> blocks = Multimaps.newListMultimap(blocksT, LinkedList::new); // LinkedList to allow .descendingIterator

        private BakedChunk(int version, NetInput in) throws IOException {
            super(version, in);

            int time = 0;
            for (int i = in.readVarInt(); i > 0; i--) {
                time += in.readVarInt();

                for (int j = in.readVarInt(); j > 0; j--) {
                    blocks.put(time, new BlockChange(
                            NetUtil.readPosition(in),
                            NetUtil.readBlockState(in),
                            NetUtil.readBlockState(in)
                    ));
                }
            }
        }

        @Override
        public void play(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            blocksT.subMap(currentTimeStamp, false, replayTime, true).values()
                    .forEach(updates -> updates.forEach(update -> {
                        send.accept(toMC(new ServerBlockChangePacket(new BlockChangeRecord(update.pos, update.to))));
                    }));
        }

        @Override
        public void rewind(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            if (currentTimeStamp >= despawnTime) {
                play(spawnTime, replayTime, send);
                return;
            }
            blocksT.subMap(replayTime, false, currentTimeStamp, true).descendingMap().values()
                    .forEach(updates ->
                            ((LinkedList<BlockChange>) updates).descendingIterator().forEachRemaining(update ->
                                    send.accept(toMC(new ServerBlockChangePacket(new BlockChangeRecord(update.pos, update.from))))));
        }
    }

    private static class BlockChange {
        private Position pos;
        private BlockState from;
        private BlockState to;

        private BlockChange(Position pos, BlockState from, BlockState to) {
            this.pos = pos;
            this.from = from;
            this.to = to;
        }
    }

    private static class Weather extends TrackedThing {
        private TreeMap<Integer, com.github.steveice10.packetlib.packet.Packet> rainStrengths = new TreeMap<>();

        private Weather() {
            super(Collections.singletonList(new ServerNotifyClientPacket(ClientNotification.START_RAIN, null)),
                    Collections.singletonList(new ServerNotifyClientPacket(ClientNotification.STOP_RAIN, null)));
        }

        @Override
        public void writeToCache(NetOutput out) throws IOException {
            super.writeToCache(out);

            QuickReplaySender.writeToCache(out, rainStrengths);
        }
    }

    private static class BakedWeather extends BakedTrackedThing {
        private TreeMap<Integer, Packet<?>> rainStrengths = new TreeMap<>();

        private BakedWeather(int version, NetInput in) throws IOException {
            super(version, in);

            readFromCache(version, in, rainStrengths);
        }

        @Override
        public void play(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            playMap(rainStrengths, currentTimeStamp, replayTime, send);
        }

        @Override
        public void rewind(int currentTimeStamp, int replayTime, Consumer<Packet<?>> send) {
            rewindMap(rainStrengths, currentTimeStamp, replayTime, send);
        }
    }
}
//#endif
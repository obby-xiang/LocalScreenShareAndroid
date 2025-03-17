package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.Empty;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.support.Preferences;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public final class LssServer {
    private static final int GRPC_SERVER_EXECUTOR_THREADS = 4;

    @NonNull
    private final ExecutorService mGrpcServerExecutor;

    @NonNull
    private final ScreenStreamService mScreenStreamService;

    @NonNull
    private final Server mGrpcServer;

    public LssServer() {
        mGrpcServerExecutor = Executors.newFixedThreadPool(GRPC_SERVER_EXECUTOR_THREADS);
        mScreenStreamService = new ScreenStreamService(mGrpcServerExecutor);
        mGrpcServer = Grpc.newServerBuilderForPort(Preferences.get().getLssServerPort(),
                InsecureServerCredentials.create())
            .executor(mGrpcServerExecutor)
            .addService(mScreenStreamService)
            .build();
    }

    public void start() throws IOException {
        mGrpcServer.start();
    }

    public void stop() {
        mScreenStreamService.stop();
        mGrpcServerExecutor.shutdownNow();
        mGrpcServer.shutdownNow();
    }

    private static class ScreenStreamService extends ScreenStreamServiceGrpc.ScreenStreamServiceImplBase {
        @Nullable
        private ScreenFrame mScreenFrame;

        @NonNull
        private final Executor mExecutor;

        @NonNull
        private final List<ServerCallStreamObserver<ScreenFrame>> mResponseObservers = new CopyOnWriteArrayList<>();

        public ScreenStreamService(@NonNull final Executor executor) {
            mExecutor = executor;
        }

        @Override
        public void getScreenStream(Empty request, StreamObserver<ScreenFrame> responseObserver) {
            final ServerCallStreamObserver<ScreenFrame> responseCallObserver =
                (ServerCallStreamObserver<ScreenFrame>) responseObserver;
            mResponseObservers.add(responseCallObserver);
            responseCallObserver.setOnCloseHandler(() -> mResponseObservers.remove(responseCallObserver));
            responseCallObserver.setOnCancelHandler(() -> mResponseObservers.remove(responseCallObserver));
        }

        public void stop() {
            mScreenFrame = null;
            mResponseObservers.forEach(StreamObserver::onCompleted);
            mResponseObservers.clear();
        }
    }
}

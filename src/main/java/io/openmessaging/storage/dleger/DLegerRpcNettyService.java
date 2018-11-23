/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openmessaging.storage.dleger;

import com.alibaba.fastjson.JSON;
import io.netty.channel.ChannelHandlerContext;
import io.openmessaging.storage.dleger.protocol.AppendEntryRequest;
import io.openmessaging.storage.dleger.protocol.DLegerRequestCode;
import io.openmessaging.storage.dleger.protocol.DLegerResponseCode;
import io.openmessaging.storage.dleger.protocol.HeartBeatRequest;
import io.openmessaging.storage.dleger.protocol.MetadataResponse;
import io.openmessaging.storage.dleger.protocol.PullEntriesRequest;
import io.openmessaging.storage.dleger.protocol.PushEntryResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import io.openmessaging.storage.dleger.protocol.AppendEntryResponse;
import io.openmessaging.storage.dleger.protocol.GetEntriesRequest;
import io.openmessaging.storage.dleger.protocol.GetEntriesResponse;
import io.openmessaging.storage.dleger.protocol.HeartBeatResponse;
import io.openmessaging.storage.dleger.protocol.MetadataRequest;
import io.openmessaging.storage.dleger.protocol.PullEntriesResponse;
import io.openmessaging.storage.dleger.protocol.PushEntryRequest;
import io.openmessaging.storage.dleger.protocol.RequestOrResponse;
import io.openmessaging.storage.dleger.protocol.VoteRequest;
import io.openmessaging.storage.dleger.protocol.VoteResponse;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyRemotingClient;
import org.apache.rocketmq.remoting.netty.NettyRemotingServer;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A netty implementation of DLegerRpcService.
 * It should be bi-directional, which means it implements both DLegerProtocol and DLegerProtocolHandler.
 */

public class DLegerRpcNettyService  extends DLegerRpcService {

    private static Logger logger = LoggerFactory.getLogger(DLegerRpcNettyService.class);

    private NettyRemotingServer remotingServer;
    private NettyRemotingClient remotingClient;


    private MemberState memberState;

    private DLegerServer dLegerServer;

    private ExecutorService futureExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private AtomicInteger threadIndex = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "FutureExecutor_" + this.threadIndex.incrementAndGet());
        }
    });



    private String getPeerAddr(RequestOrResponse request) {
        //support different groups in the near future
        return memberState.getPeerAddr(request.getRemoteId());
    }



    public DLegerRpcNettyService(DLegerServer dLegerServer) {
        this.dLegerServer = dLegerServer;
        this.memberState = dLegerServer.getMemberState();
        NettyRequestProcessor protocolProcessor = new NettyRequestProcessor() {
            @Override
            public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws Exception {
                return DLegerRpcNettyService.this.processRequest(ctx, request);
            }

            @Override public boolean rejectRequest() {
                return false;
            }
        };
        //start the remoting server
        NettyServerConfig nettyServerConfig = new NettyServerConfig();
        nettyServerConfig.setListenPort(Integer.valueOf(memberState.getSelfAddr().split(":")[1]));
        this.remotingServer = new NettyRemotingServer(nettyServerConfig, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.METADATA.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.APPEND.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.GET.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.PULL.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.PUSH.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.VOTE.getCode(), protocolProcessor, null);
        this.remotingServer.registerProcessor(DLegerRequestCode.HEART_BEAT.getCode(), protocolProcessor, null);

        //start the remoting client
        this.remotingClient = new NettyRemotingClient(new NettyClientConfig(), null);


    }

    @Override public CompletableFuture<HeartBeatResponse> heartBeat(HeartBeatRequest request) throws Exception {
        CompletableFuture<HeartBeatResponse> future = new CompletableFuture<>();
        try {
            RemotingCommand wrapperRequest =  RemotingCommand.createRequestCommand(DLegerRequestCode.HEART_BEAT.getCode(), null);
            wrapperRequest.setBody(JSON.toJSONBytes(request));
            remotingClient.invokeAsync(getPeerAddr(request), wrapperRequest, 3000, responseFuture -> {
                HeartBeatResponse  response = JSON.parseObject(responseFuture.getResponseCommand().getBody(), HeartBeatResponse.class);
                future.complete(response);
            });
        } catch (Throwable t) {
            logger.error("Send heartBeat request failed {}", request.baseInfo(), t);
            future.complete(new HeartBeatResponse().code(DLegerResponseCode.NETWORK_ERROR.getCode()));
        }
        return future;
    }

    @Override public CompletableFuture<VoteResponse> vote(VoteRequest request) throws Exception {
        CompletableFuture<VoteResponse> future = new CompletableFuture<>();
        try {
            RemotingCommand wrapperRequest =  RemotingCommand.createRequestCommand(DLegerRequestCode.VOTE.getCode(), null);
            wrapperRequest.setBody(JSON.toJSONBytes(request));
            remotingClient.invokeAsync(getPeerAddr(request), wrapperRequest, 3000, responseFuture -> {
                VoteResponse  response = JSON.parseObject(responseFuture.getResponseCommand().getBody(), VoteResponse.class);
                future.complete(response);
            });
        } catch (Throwable t) {
            logger.error("Send vote request failed {}", request.baseInfo(), t);
            future.complete(new VoteResponse());
        }
        return future;
    }

    @Override public CompletableFuture<GetEntriesResponse> get(GetEntriesRequest request) throws Exception {
        GetEntriesResponse entriesResponse = new GetEntriesResponse();
        entriesResponse.setCode(DLegerResponseCode.UNSUPPORTED.getCode());
        return CompletableFuture.completedFuture(entriesResponse);
    }

    @Override public CompletableFuture<AppendEntryResponse> append(AppendEntryRequest request) throws Exception {
        CompletableFuture<AppendEntryResponse> future = new CompletableFuture<>();
        try {
            RemotingCommand wrapperRequest =  RemotingCommand.createRequestCommand(DLegerRequestCode.APPEND.getCode(), null);
            wrapperRequest.setBody(JSON.toJSONBytes(request));
            remotingClient.invokeAsync(getPeerAddr(request), wrapperRequest, 3000, responseFuture -> {
                AppendEntryResponse  response = JSON.parseObject(responseFuture.getResponseCommand().getBody(), AppendEntryResponse.class);
                future.complete(response);
            });
        } catch (Throwable t) {
            logger.error("Send append request failed {}", request.baseInfo(), t);
            AppendEntryResponse response = new AppendEntryResponse();
            response.copyBaseInfo(request);
            response.setCode(DLegerResponseCode.NETWORK_ERROR.getCode());
            future.complete(response);
        }
        return future;
    }

    @Override public CompletableFuture<MetadataResponse> metadata(MetadataRequest request) throws Exception {
        MetadataResponse metadataResponse = new MetadataResponse();
        metadataResponse.setCode(DLegerResponseCode.UNSUPPORTED.getCode());
        return CompletableFuture.completedFuture(metadataResponse);
    }

    @Override public CompletableFuture<PullEntriesResponse> pull(PullEntriesRequest request) throws Exception {
        RemotingCommand wrapperRequest =  RemotingCommand.createRequestCommand(DLegerRequestCode.PULL.getCode(), null);
        wrapperRequest.setBody(JSON.toJSONBytes(request));
        RemotingCommand wrapperResponse = remotingClient.invokeSync(getPeerAddr(request), wrapperRequest, 3000);
        PullEntriesResponse  response = JSON.parseObject(wrapperResponse.getBody(), PullEntriesResponse.class);
        return CompletableFuture.completedFuture(response);
    }

    @Override public CompletableFuture<PushEntryResponse> push(PushEntryRequest request) throws Exception {
        CompletableFuture<PushEntryResponse> future = new CompletableFuture<>();
        try {
            RemotingCommand wrapperRequest =  RemotingCommand.createRequestCommand(DLegerRequestCode.PUSH.getCode(), null);
            wrapperRequest.setBody(JSON.toJSONBytes(request));
            remotingClient.invokeAsync(getPeerAddr(request), wrapperRequest, 3000, responseFuture -> {
                PushEntryResponse  response = JSON.parseObject(responseFuture.getResponseCommand().getBody(), PushEntryResponse.class);
                future.complete(response);
            });
        } catch (Throwable t) {
            logger.error("Send push request failed {}", request.baseInfo(), t);
            PushEntryResponse response = new PushEntryResponse();
            response.copyBaseInfo(request);
            response.setCode(DLegerResponseCode.NETWORK_ERROR.getCode());
            future.complete(response);
        }

        return future;
    }


    private void writeResponse(RequestOrResponse storeResp, Throwable t, RemotingCommand request, ChannelHandlerContext ctx) {
        RemotingCommand response = null;
        try {
            if (t != null) {
                //the t should be null, using error code instead
                throw t;
            } else {
                response = handleResponse(storeResp, request);
                response.markResponseType();
                ctx.writeAndFlush(response);
            }
        } catch (Throwable e) {
            logger.error("Process request over, but fire response failed, request:[{}] response:[{}]", request, response, e);
        }
    }

    /**
     * The core method to handle rpc requests.
     * The advantages of using future instead of callback:
     *
     *  1. separate the caller from actual executor, which make it able to handle the future results by the caller's wish
     *  2. simplify the later execution method
     *
     * CompletableFuture is an excellent choice, whenCompleteAsync will handle the response asynchronously.
     * With an independent thread-pool, it will improve performance and reduce blocking points.
     * @param ctx
     * @param request
     * @return
     * @throws Exception
     */
    public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws Exception {
        DLegerRequestCode requestCode = DLegerRequestCode.valueOf(request.getCode());
        switch (requestCode) {
            case METADATA:
            {
                MetadataRequest metadataRequest = JSON.parseObject(request.getBody(), MetadataRequest.class);
                CompletableFuture<MetadataResponse> future = handleMetadata(metadataRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case APPEND:
            {
                AppendEntryRequest appendEntryRequest = JSON.parseObject(request.getBody(), AppendEntryRequest.class);
                CompletableFuture<AppendEntryResponse> future = handleAppend(appendEntryRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case GET:
            {
                GetEntriesRequest getEntriesRequest = JSON.parseObject(request.getBody(), GetEntriesRequest.class);
                CompletableFuture<GetEntriesResponse> future = handleGet(getEntriesRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case PULL:
            {
                PullEntriesRequest pullEntriesRequest = JSON.parseObject(request.getBody(), PullEntriesRequest.class);
                CompletableFuture<PullEntriesResponse> future = handlePull(pullEntriesRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case PUSH:
            {
                PushEntryRequest pushEntryRequest = JSON.parseObject(request.getBody(), PushEntryRequest.class);
                CompletableFuture<PushEntryResponse> future = handlePush(pushEntryRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case VOTE:
            {
                VoteRequest voteRequest = JSON.parseObject(request.getBody(), VoteRequest.class);
                CompletableFuture<VoteResponse> future = handleVote(voteRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            case HEART_BEAT:
            {
                HeartBeatRequest heartBeatRequest = JSON.parseObject(request.getBody(), HeartBeatRequest.class);
                CompletableFuture<HeartBeatResponse> future = handleHeartBeat(heartBeatRequest);
                future.whenCompleteAsync((x, y) -> { writeResponse(x, y, request, ctx);}, futureExecutor);
                break;
            }
            default:
                logger.error("Unknown request code {} from {}", request.getCode(), request);
                break;
        }
        return null;
    }


    @Override
    public CompletableFuture<HeartBeatResponse> handleHeartBeat(HeartBeatRequest request) throws Exception {
        return dLegerServer.handleHeartBeat(request);
    }

    @Override
    public CompletableFuture<VoteResponse> handleVote(VoteRequest request) throws Exception {
        VoteResponse response = dLegerServer.handleVote(request).get();
        return CompletableFuture.completedFuture(response);
    }


    @Override
    public CompletableFuture<AppendEntryResponse> handleAppend(AppendEntryRequest request) throws Exception {
        return dLegerServer.handleAppend(request);
    }

    @Override public CompletableFuture<GetEntriesResponse> handleGet(GetEntriesRequest request) throws Exception {
        return dLegerServer.handleGet(request);
    }

    @Override public CompletableFuture<MetadataResponse> handleMetadata(MetadataRequest request) throws Exception {
        return dLegerServer.handleMetadata(request);
    }

    @Override
    public CompletableFuture<PullEntriesResponse> handlePull(PullEntriesRequest request) throws Exception {
        return dLegerServer.handlePull(request);
    }

    @Override public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
        return dLegerServer.handlePush(request);
    }

    public RemotingCommand handleResponse(RequestOrResponse response, RemotingCommand request) {
        RemotingCommand remotingCommand = RemotingCommand.createResponseCommand(DLegerResponseCode.SUCCESS.getCode(), null);
        remotingCommand.setBody(JSON.toJSONBytes(response));
        remotingCommand.setOpaque(request.getOpaque());
        return remotingCommand;
    }


    @Override
    public void startup() {
        this.remotingServer.start();
        this.remotingClient.start();
    }

    @Override
    public void shutdown() {
        this.remotingServer.shutdown();
        this.remotingClient.shutdown();
    }

    public MemberState getMemberState() {
        return memberState;
    }

    public void setMemberState(MemberState memberState) {
        this.memberState = memberState;
    }

    public DLegerServer getdLegerServer() {
        return dLegerServer;
    }

    public void setdLegerServer(DLegerServer dLegerServer) {
        this.dLegerServer = dLegerServer;
    }
}
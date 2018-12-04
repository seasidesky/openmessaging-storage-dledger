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

package io.openmessaging.storage.dledger.protocol;

import io.openmessaging.storage.dledger.DLedgerServer;
import io.openmessaging.storage.dledger.ServerTestHarness;
import io.openmessaging.storage.dledger.utils.UtilAll;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;

public class VoteRequestTest extends ServerTestHarness {

    @Test
    public void testVoteNormal() throws Exception {
        String group = UUID.randomUUID().toString();
        String peers = String.format("n0-localhost:%d;n1-localhost:%d", nextPort(), nextPort());
        DLedgerServer dLedgerServer0 = launchServer(group, peers, "n0");
        DLedgerServer dLedgerServer1 = launchServer(group, peers, "n1");
        long start = System.currentTimeMillis();
        while (!dLedgerServer0.getMemberState().isLeader() && !dLedgerServer1.getMemberState().isLeader() && UtilAll.elapsed(start) < 3000) {
            Thread.sleep(100);
        }
        Thread.sleep(300);
        Assert.assertTrue(dLedgerServer0.getMemberState().isLeader() || dLedgerServer1.getMemberState().isLeader());
        DLedgerServer leader, follower;
        if (dLedgerServer0.getMemberState().isLeader()) {
            leader = dLedgerServer0;
            follower = dLedgerServer1;
        } else {
            leader = dLedgerServer1;
            follower = dLedgerServer0;
        }
        Assert.assertTrue(leader.getMemberState().isLeader());
        Assert.assertTrue(follower.getMemberState().isFollower());
        Assert.assertEquals(leader.getMemberState().getSelfId(), leader.getMemberState().getLeaderId());
        Assert.assertEquals(leader.getMemberState().getLeaderId(), follower.getMemberState().getLeaderId());
        Assert.assertEquals(leader.getMemberState().currTerm(), follower.getMemberState().currTerm());
        Assert.assertEquals(-1, leader.getMemberState().getLegerEndIndex());
        Assert.assertEquals(-1, follower.getMemberState().getLegerEndIndex());
        Assert.assertTrue(leader.getMemberState().getLegerEndIndex() >= follower.getMemberState().getLegerEndIndex());
        Assert.assertTrue(leader.getMemberState().getLegerEndTerm() >= follower.getMemberState().getLegerEndTerm());

        {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setGroup(group);
            voteRequest.setRemoteId(leader.getMemberState().getSelfId());
            voteRequest.setTerm(leader.getMemberState().currTerm());
            voteRequest.setLeaderId("n2");
            Assert.assertEquals(VoteResponse.RESULT.REJECT_UNKNOWN_LEADER, leader.handleVote(voteRequest).get().getVoteResult());
        }

        {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setGroup(group);
            voteRequest.setTerm(leader.getMemberState().currTerm());
            voteRequest.setLeaderId(leader.getMemberState().getSelfId());
            Assert.assertEquals(VoteResponse.RESULT.ACCEPT, leader.getdLegerLeaderElector().handleVote(voteRequest, true).get().getVoteResult());
            voteRequest.setRemoteId(follower.getMemberState().getSelfId());
            Assert.assertEquals(VoteResponse.RESULT.ACCEPT, follower.handleVote(voteRequest).get().getVoteResult());
            voteRequest.setRemoteId(leader.getMemberState().getSelfId());
            Assert.assertEquals(VoteResponse.RESULT.REJECT_UNEXPECTED_LEADER, leader.handleVote(voteRequest).get().getVoteResult());
        }
        {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setGroup(group);
            voteRequest.setRemoteId(leader.getMemberState().getSelfId());
            voteRequest.setLeaderId(follower.getMemberState().getSelfId());
            voteRequest.setTerm(leader.getMemberState().currTerm() - 1);
            Assert.assertEquals(VoteResponse.RESULT.REJECT_EXPIRED_VOTE_TERM, leader.handleVote(voteRequest).get().getVoteResult());
        }

        {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setGroup(group);
            voteRequest.setRemoteId(leader.getMemberState().getSelfId());
            voteRequest.setTerm(leader.getMemberState().currTerm());
            voteRequest.setLeaderId(follower.getMemberState().getSelfId());
            Assert.assertEquals(VoteResponse.RESULT.REJECT_ALREADY__HAS_LEADER, leader.handleVote(voteRequest).get().getVoteResult());
        }

        {
            long endTerm = leader.getMemberState().getLegerEndTerm();
            long endIndex = leader.getMemberState().getLegerEndIndex();
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setGroup(group);
            voteRequest.setRemoteId(leader.getMemberState().getSelfId());
            voteRequest.setTerm(leader.getMemberState().currTerm());
            voteRequest.setLeaderId(leader.getMemberState().getSelfId());
            voteRequest.setLegerEndTerm(endTerm);
            voteRequest.setLegerEndIndex(endIndex);

            leader.getMemberState().updateLegerIndexAndTerm(endIndex, endTerm + 1);
            Assert.assertEquals(VoteResponse.RESULT.REJECT_EXPIRED_LEGER_TERM, leader.getdLegerLeaderElector().handleVote(voteRequest, true).get().getVoteResult());
            leader.getMemberState().updateLegerIndexAndTerm(endIndex + 1, endTerm);
            Assert.assertEquals(VoteResponse.RESULT.REJECT_SMALL_LEGER_END_INDEX, leader.getdLegerLeaderElector().handleVote(voteRequest, true).get().getVoteResult());

        }
    }

    @Test
    public void testVoteTermSmallThanLeger() throws Exception {
        String group = UUID.randomUUID().toString();
        String peers = String.format("n0-localhost:%d", nextPort(), nextPort());
        DLedgerServer leader = launchServer(group, peers, "n0");
        Thread.sleep(1000);
        Assert.assertTrue(leader.getMemberState().isLeader());

        long term = leader.getMemberState().currTerm();
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setGroup(group);
        voteRequest.setTerm(term);
        voteRequest.setLeaderId(leader.getMemberState().getSelfId());
        voteRequest.setLegerEndTerm(term + 1);
        voteRequest.setLegerEndIndex(leader.getMemberState().getLegerEndIndex());

        leader.getMemberState().updateLegerIndexAndTerm(leader.getMemberState().getLegerEndIndex(), term + 1);

        Assert.assertEquals(VoteResponse.RESULT.REJECT_TERM_SMALL_THAN_LEGER, leader.getdLegerLeaderElector().handleVote(voteRequest, true).get().getVoteResult());

        leader.getMemberState().changeToCandidate(term);

        long start = System.currentTimeMillis();

        while (!leader.getMemberState().isLeader() && UtilAll.elapsed(start) < 3000) {
            Thread.sleep(300);
        }

        Assert.assertTrue(leader.getMemberState().isLeader());
        Assert.assertEquals(term + 1, leader.getMemberState().currTerm());

    }

    @Test
    public void testVoteAlreadyVoted() throws Exception {
        String group = UUID.randomUUID().toString();
        String peers = String.format("n0-localhost:%d", nextPort(), nextPort());
        DLedgerServer leader = launchServer(group, peers, "n0");
        Thread.sleep(1000);
        Assert.assertTrue(leader.getMemberState().isLeader());

        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setGroup(group);
        voteRequest.setTerm(leader.getMemberState().currTerm());
        voteRequest.setLeaderId(leader.getMemberState().getSelfId());
        voteRequest.setLegerEndIndex(leader.getMemberState().getLegerEndIndex());
        voteRequest.setLegerEndTerm(leader.getMemberState().getLegerEndTerm());

        leader.getMemberState().changeToCandidate(leader.getMemberState().currTerm());
        leader.getMemberState().setCurrVoteFor("n2");

        Assert.assertEquals(VoteResponse.RESULT.REJECT_ALREADY_VOTED, leader.getdLegerLeaderElector().handleVote(voteRequest, true).get().getVoteResult());

        long start = System.currentTimeMillis();

        while (!leader.getMemberState().isLeader() && UtilAll.elapsed(start) < 3000) {
            Thread.sleep(300);
        }

        Assert.assertTrue(leader.getMemberState().isLeader());
    }

}

/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.lucene.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.inmemory.InMemoryId;
import org.apache.james.mailbox.inmemory.InMemoryMessageId;
import org.apache.james.mailbox.inmemory.manager.InMemoryIntegrationResources;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.searchhighligt.SearchHighLighterContract;
import org.apache.james.mailbox.searchhighligt.SearchHighlighter;
import org.apache.james.mailbox.searchhighligt.SearchHighlighterConfiguration;
import org.apache.james.mailbox.searchhighligt.SearchSnippet;
import org.apache.james.mailbox.store.StoreMailboxManager;
import org.apache.james.mailbox.store.StoreMessageManager;
import org.apache.james.mailbox.store.extractor.JsoupTextExtractor;
import org.apache.james.mailbox.store.search.MessageSearchIndex;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.util.ClassLoaderUtils;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;

import reactor.core.publisher.Flux;

class LuceneMemorySearchHighLighterTest implements SearchHighLighterContract {
    private MessageSearchIndex messageSearchIndex;
    private StoreMailboxManager storeMailboxManager;
    private StoreMessageManager inboxMessageManager;
    private LuceneSearchHighlighter testee;

    @BeforeEach
    public void setUp() throws Exception {
        final MessageId.Factory messageIdFactory = new InMemoryMessageId.Factory();
        InMemoryIntegrationResources resources = InMemoryIntegrationResources.builder()
            .preProvisionnedFakeAuthenticator()
            .fakeAuthorizator()
            .inVmEventBus()
            .defaultAnnotationLimits()
            .defaultMessageParser()
            .listeningSearchIndex(Throwing.function(preInstanciationStage -> new LuceneMessageSearchIndex(
                preInstanciationStage.getMapperFactory(), new InMemoryId.Factory(), new ByteBuffersDirectory(),
                messageIdFactory,
                preInstanciationStage.getSessionProvider(), new JsoupTextExtractor())))
            .noPreDeletionHooks()
            .storeQuotaManager()
            .build();
        storeMailboxManager = resources.getMailboxManager();
        messageSearchIndex = resources.getSearchIndex();
        testee = new LuceneSearchHighlighter(((LuceneMessageSearchIndex) messageSearchIndex),
            SearchHighlighterConfiguration.DEFAULT,
            messageIdFactory, storeMailboxManager);

        MailboxSession session = storeMailboxManager.createSystemSession(USERNAME1);
        MailboxPath inboxPath = MailboxPath.inbox(USERNAME1);
        storeMailboxManager.createMailbox(inboxPath, session);
        inboxMessageManager = (StoreMessageManager) storeMailboxManager.getMailbox(inboxPath, session);
    }

    @Override
    public SearchHighlighter testee() {
        return testee;
    }

    @Override
    public MailboxSession session(Username username) {
        return storeMailboxManager.createSystemSession(username);
    }

    @Override
    public MessageManager.AppendResult appendMessage(MessageManager.AppendCommand appendCommand, MailboxSession session) {
        return Throwing.supplier(() -> inboxMessageManager.appendMessage(appendCommand, session)).get();
    }

    @Override
    public MailboxId randomMailboxId(Username username) {
        String random = new String(new byte[8]);
        return Throwing.supplier(() -> storeMailboxManager.createMailbox(MailboxPath.forUser(USERNAME1, random), session(username)).get()).get();
    }

    @Override
    public void verifyMessageWasIndexed(int indexedMessageCount) throws MailboxException {
        assertThat(messageSearchIndex.search(session(USERNAME1), inboxMessageManager.getMailboxEntity(), SearchQuery.of()).toStream().count())
            .isEqualTo(indexedMessageCount);
    }

    @Override
    public void applyRightsCommand(MailboxId mailboxId, Username owner, Username delegated) {
        Mailbox mailbox = inboxMessageManager.getMailboxEntity();
        Throwing.runnable(() -> storeMailboxManager.applyRightsCommand(mailbox.generateAssociatedPath(),
            MailboxACL.command().forUser(delegated).rights(MailboxACL.FULL_RIGHTS).asAddition(),
            session(owner))).run();
    }


    @Test
    void shouldHighlightAttachmentTextContentWhenTextBodyDoesNotMatch() throws Exception {
        assumeTrue(storeMailboxManager.getSupportedSearchCapabilities().contains(MailboxManager.SearchCapabilities.Attachment));
        MailboxSession session = session(USERNAME1);

        ComposedMessageId m1 = appendMessage(MessageManager.AppendCommand.from(
                Message.Builder.of()
                    .setTo("to@james.local")
                    .setSubject("Hallo, Thx Matthieu for your help")
                    .setBody("append contentA to inbox", StandardCharsets.UTF_8)),
            session).getId();

        // m2 has an attachment with text content: "This is a beautiful banana"
        ComposedMessageId m2 = inboxMessageManager.appendMessage(
            MessageManager.AppendCommand.builder()
                .build(ClassLoaderUtils.getSystemResourceAsSharedStream("eml/emailWithTextAttachment.eml")),
            session).getId();

        verifyMessageWasIndexed(2);

        String keywordSearch = "beautiful";
        MultimailboxesSearchQuery multiMailboxSearch = MultimailboxesSearchQuery.from(SearchQuery.of(
                new SearchQuery.ConjunctionCriterion(SearchQuery.Conjunction.OR,
                    List.of(SearchQuery.bodyContains(keywordSearch),
                        SearchQuery.attachmentContains(keywordSearch)))))
            .inMailboxes(List.of(m1.getMailboxId(), m2.getMailboxId()))
            .build();

        List<SearchSnippet> searchSnippets = Flux.from(testee().highlightSearch(List.of(m1.getMessageId(), m2.getMessageId()), multiMailboxSearch, session))
            .collectList()
            .block();

        assertThat(searchSnippets).hasSize(1);

        assertThat(searchSnippets.getFirst().highlightedBody())
            .isPresent()
            .satisfies(highlightedBody -> {
                assertThat(highlightedBody.get()).contains("This is a <mark>beautiful</mark> banana");
            });
    }
}

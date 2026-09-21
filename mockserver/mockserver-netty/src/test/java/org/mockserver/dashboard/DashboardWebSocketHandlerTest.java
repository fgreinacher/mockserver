package org.mockserver.dashboard;

import com.google.common.base.Joiner;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.unification.PortUnificationHandler.http2Enabled;

public class DashboardWebSocketHandlerTest {

    @Test
    public void shouldSerialiseEventsAndIgnoreDeletedLogEvents() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo")
                .setDeleted(true),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat")
                .setDeleted(true),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo")
                .setDeleted(true),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
                .setDeleted(true)
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(10).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(10).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(10).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(10).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(8).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(8).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(8).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(8).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatTwo\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(6).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(6).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(6).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(6).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatOne\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\",\n" +
            "        \"value\" : \"messagePartOne:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentOne\\\"\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\",\n" +
            "        \"value\" : \"messagePartTwo:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentTwo\\\"\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(10).id() + "_request\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(8).id() + "_request\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(6).id() + "_request\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithNoRequestFilter() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(5).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(5).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatTwo\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatOne\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\",\n" +
            "        \"value\" : \"messagePartOne:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentOne\\\"\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\",\n" +
            "        \"value\" : \"messagePartTwo:\"\n" +
            "      }, {\n" +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\",\n" +
            "        \"multiline\" : false,\n" +
            "        \"argument\" : true,\n" +
            "        \"value\" : \"\\\"argumentTwo\\\"\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_request\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(4).id() + "_request\"\n" +
            "  }, {\n" +
            "    \"description\" : \"  /somePathOne\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathOne\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(3).id() + "_request\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithRequestFilter() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormat"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathOne"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/somePathTwo"))
                .setMessageFormat("messageFormatThree")
        );
        String renderedList = "{\n" +
            "  \"logMessages\" : [ {\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(5).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(5).getTimestamp(), "-") + " RECEIVED_REQUEST   \",\n" +
            "      \"style\" : {\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(114,160,193)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(5).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormatThree\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  }, {\n" +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\",\n" +
            "    \"value\" : {\n" +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\",\n" +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO               \",\n" +
            "      \"style\" : {\n" +
            "        \"style.whiteSpace\" : \"pre-wrap\",\n" +
            "        \"paddingBottom\" : \"4px\",\n" +
            "        \"whiteSpace\" : \"nowrap\",\n" +
            "        \"overflow\" : \"auto\",\n" +
            "        \"color\" : \"rgb(59,122,87)\",\n" +
            "        \"paddingTop\" : \"4px\"\n" +
            "      },\n" +
            "      \"messageParts\" : [ {\n" +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\",\n" +
            "        \"value\" : \"messageFormat\"\n" +
            "      } ]\n" +
            "    }\n" +
            "  } ],\n" +
            "  \"recordedRequests\" : [ {\n" +
            "    \"description\" : \"  /somePathTwo\",\n" +
            "    \"value\" : {\n" +
            "      \"httpRequest\" : {\n" +
            "        \"path\" : \"/somePathTwo\"\n" +
            "      }\n" +
            "    },\n" +
            "    \"key\" : \"" + logEntries.get(5).id() + "_request\"\n" +
            "  } ]\n" +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request("/somePathTwo"), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseExpectationsWithRequestFilter() throws InterruptedException {
        // given
        List<Expectation> expectations = Arrays.asList(
            new Expectation(request("one")).thenRespond(response("one")),
            new Expectation(request("two")).thenRespond(response("two")),
            new Expectation(request("three")).thenRespond(response("three"))
        );
        String renderedList = "" +
            "  \"activeExpectations\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(0).getId() + ":   one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request("one"), Collections.emptyList(), expectations, renderedList);
    }

    @Test
    public void shouldSerialiseMessageWithException() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setMessageFormat("messageFormat")
                .setThrowable(new RuntimeException("TEST EXCEPTION"))
        );
        String[] renderedList = new String[]{
            "{" + NEW_LINE +
                "  \"logMessages\" : [ {" + NEW_LINE +
                "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
                "    \"value\" : {" + NEW_LINE +
                "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
                "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
                "      \"style\" : {" + NEW_LINE +
                "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
                "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
                "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
                "        \"overflow\" : \"auto\"," + NEW_LINE +
                "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
                "        \"paddingTop\" : \"4px\"" + NEW_LINE +
                "      }," + NEW_LINE +
                "      \"messageParts\" : [ {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
                "        \"value\" : \"messageFormat\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_throwable_msg\"," + NEW_LINE +
                "        \"value\" : \"exception:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(1).id() + "_throwable_value\"," + NEW_LINE +
                "        \"multiline\" : true," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : [ \"java.lang.RuntimeException: TEST EXCEPTION\", \"\\tat org.mockserver.dashboard.DashboardWebSocketHandlerTest.shouldSerialiseMessageWithException",
            "      } ]" + NEW_LINE +
                "    }" + NEW_LINE +
                "  }, {" + NEW_LINE +
                "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
                "    \"value\" : {" + NEW_LINE +
                "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
                "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
                "      \"style\" : {" + NEW_LINE +
                "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
                "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
                "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
                "        \"overflow\" : \"auto\"," + NEW_LINE +
                "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
                "        \"paddingTop\" : \"4px\"" + NEW_LINE +
                "      }," + NEW_LINE +
                "      \"messageParts\" : [ {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
                "        \"value\" : \"messagePartOne:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
                "        \"multiline\" : false," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
                "        \"value\" : \"messagePartTwo:\"" + NEW_LINE +
                "      }, {" + NEW_LINE +
                "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
                "        \"multiline\" : false," + NEW_LINE +
                "        \"argument\" : true," + NEW_LINE +
                "        \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
                "      } ]" + NEW_LINE +
                "    }" + NEW_LINE +
                "  } ]" + NEW_LINE +
                "}"};

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithRequest() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("two"))
                .setMessageFormat("messageFormat")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormat\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messagePartOne:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
            "        \"multiline\" : false," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
            "        \"value\" : \"messagePartTwo:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
            "        \"multiline\" : false," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRollUpEventsWithCorrelationId() throws InterruptedException {
        // given
        String logCorrelationId = UUIDService.getUUID();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(logCorrelationId))
                .setMessageFormat("messagePartOne:{}messagePartTwo:{}")
                .setArguments("argumentOne", "argumentTwo"),
            new LogEntry()
                .setHttpRequest(request("two").withLogCorrelationId(logCorrelationId))
                .setMessageFormat("messageFormat")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log_group\"," + NEW_LINE +
            "    \"group\" : {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"value\" : [ {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormat\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }, {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messagePartOne:\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0arg\"," + NEW_LINE +
            "          \"multiline\" : false," + NEW_LINE +
            "          \"argument\" : true," + NEW_LINE +
            "          \"value\" : \"\\\"argumentOne\\\"\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_1msg\"," + NEW_LINE +
            "          \"value\" : \"messagePartTwo:\"" + NEW_LINE +
            "        }, {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_1arg\"," + NEW_LINE +
            "          \"multiline\" : false," + NEW_LINE +
            "          \"argument\" : true," + NEW_LINE +
            "          \"value\" : \"\\\"argumentTwo\\\"\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    } ]" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRollUpEventsWithSameCorrelationIdAndNotWarpEventsWithUniqueCorrelationId() throws InterruptedException {
        // given
        String logCorrelationIdShared = UUIDService.getUUID();
        String logCorrelationIdOne = UUIDService.getUUID();
        String logCorrelationIdTwo = UUIDService.getUUID();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(logCorrelationIdShared))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setHttpRequest(request("two").withLogCorrelationId(logCorrelationIdShared))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setHttpRequest(request("three").withLogCorrelationId(logCorrelationIdOne))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setHttpRequest(request("four").withLogCorrelationId(logCorrelationIdTwo))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log_group\"," + NEW_LINE +
            "    \"group\" : {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"value\" : [ {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }, {" + NEW_LINE +
            "      \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "      \"value\" : {" + NEW_LINE +
            "        \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "        \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "        \"style\" : {" + NEW_LINE +
            "          \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "          \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "          \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "          \"overflow\" : \"auto\"," + NEW_LINE +
            "          \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "          \"paddingTop\" : \"4px\"" + NEW_LINE +
            "        }," + NEW_LINE +
            "        \"messageParts\" : [ {" + NEW_LINE +
            "          \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "          \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "        } ]" + NEW_LINE +
            "      }" + NEW_LINE +
            "    } ]" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseEventsWithoutFields() throws InterruptedException {
        // given
        RuntimeException throwable = new RuntimeException();
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setHttpRequest(request("one").withLogCorrelationId(UUIDService.getUUID())),
            new LogEntry()
                .setHttpRequest(request("two")),
            new LogEntry()
                .setMessageFormat("messageFormatTwo"),
            new LogEntry(),
            new LogEntry()
                .setThrowable(throwable)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(4).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(4).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(4).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"RuntimeException\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_throwable_msg\"," + NEW_LINE +
            "        \"value\" : \"exception:\"" + NEW_LINE +
            "      }, {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(4).id() + "_throwable_value\"," + NEW_LINE +
            "        \"multiline\" : true," + NEW_LINE +
            "        \"argument\" : true," + NEW_LINE +
            "        \"value\" : [ " + Joiner.on(", ").join(Arrays.stream(getStackTrace(throwable).split(System.lineSeparator())).map(line -> "\"" + line.replaceAll("\\t", "\\\\t") + "\"").collect(Collectors.toList())) + " ]" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " INFO   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"style.whiteSpace\" : \"pre-wrap\"," + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(59,122,87)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRecordedRequests() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("three"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("four"))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"recordedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"   four\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"four\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_request\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_request\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_request\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_request\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseRecordedRequestsEventsWithoutFields() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("two")),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " RECEIVED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(114,160,193)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"recordedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"  two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_request\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldPairRecordedRequestWithMatchingResponseByCorrelationId() throws InterruptedException {
        // given — three lifecycles, all distinguished by correlationId:
        //   corr-A: RECEIVED_REQUEST paired with EXPECTATION_RESPONSE (mock match)
        //   corr-B: RECEIVED_REQUEST paired with NO_MATCH_RESPONSE (404 path)
        //   corr-C: RECEIVED_REQUEST with no matching response (response not yet logged)
        // The reverse-chronological stream surfaces each response BEFORE its
        // own request, so DashboardWebSocketHandler can stash responses by
        // correlationId in a single pass and look them up when the matching
        // request is processed.
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/matched"))
                .setCorrelationId("corr-A"),
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setHttpResponse(response().withStatusCode(200).withBody("matched-body"))
                .setCorrelationId("corr-A"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/unmatched"))
                .setCorrelationId("corr-B"),
            new LogEntry()
                .setType(NO_MATCH_RESPONSE)
                .setHttpResponse(response().withStatusCode(404).withBody("not-found-body"))
                .setCorrelationId("corr-B"),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request("/orphan"))
                .setCorrelationId("corr-C")
        );

        // The matched request must carry the paired EXPECTATION_RESPONSE body.
        String matchedFragment =
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/matched\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"body\" : \"matched-body\"" + NEW_LINE +
            "      }";

        // The unmatched request must carry the paired NO_MATCH_RESPONSE body.
        String unmatchedFragment =
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/unmatched\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 404," + NEW_LINE +
            "        \"body\" : \"not-found-body\"" + NEW_LINE +
            "      }";

        // The orphan request must emit { httpRequest } only — no httpResponse
        // key — proving the pairing degrades gracefully when the response log
        // entry is missing.
        String orphanFragment =
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"/orphan\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), logEntries, Collections.emptyList(),
            matchedFragment, unmatchedFragment, orphanFragment);
    }

    @Test
    public void shouldSerialiseForwardedRequests() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("one"))
                .setHttpResponse(response("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("two"))
                .setHttpResponse(response("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("three"))
                .setHttpResponse(response("three"))
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("four"))
                .setHttpResponse(response("four"))
                .setMessageFormat("messageFormatFour")
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(3).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatFour\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"proxiedRequests\" : [ {" + NEW_LINE +
            "    \"description\" : \"   four\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"four\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"four\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_proxied\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"three\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_proxied\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_proxied\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"    one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_proxied\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseForwardedRequestsForEventsWithoutFields() throws InterruptedException {
        // given
        List<LogEntry> logEntries = Arrays.asList(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("one"))
                .setMessageFormat("messageFormatOne"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpResponse(response("two"))
                .setMessageFormat("messageFormatTwo"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setMessageFormat("messageFormatThree"),
            new LogEntry()
                .setType(FORWARDED_REQUEST)
        );
        String renderedList = "{" + NEW_LINE +
            "  \"logMessages\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(3).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(3).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(3).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(2).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(2).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(2).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(2).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatThree\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(1).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(1).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(1).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatTwo\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_log\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"timestamp\" : \"" + logEntries.get(0).getTimestamp() + "\"," + NEW_LINE +
            "      \"description\" : \"" + StringUtils.substringAfter(logEntries.get(0).getTimestamp(), "-") + " FORWARDED_REQUEST   \"," + NEW_LINE +
            "      \"style\" : {" + NEW_LINE +
            "        \"paddingBottom\" : \"4px\"," + NEW_LINE +
            "        \"whiteSpace\" : \"nowrap\"," + NEW_LINE +
            "        \"overflow\" : \"auto\"," + NEW_LINE +
            "        \"color\" : \"rgb(152, 208, 255)\"," + NEW_LINE +
            "        \"paddingTop\" : \"4px\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"messageParts\" : [ {" + NEW_LINE +
            "        \"key\" : \"" + logEntries.get(0).id() + "_0msg\"," + NEW_LINE +
            "        \"value\" : \"messageFormatOne\"" + NEW_LINE +
            "      } ]" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]," + NEW_LINE +
            "  \"proxiedRequests\" : [ {" + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(1).id() + "_proxied\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"description\" : \"  one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"key\" : \"" + logEntries.get(0).id() + "_proxied\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(false, request(), logEntries, Collections.emptyList(), renderedList);
    }

    @Test
    public void shouldSerialiseExpectations() throws InterruptedException {
        // given
        List<Expectation> expectations = Arrays.asList(
            new Expectation(request("one")).thenRespond(response("one")),
            new Expectation(request("two")).thenRespond(response("two")),
            new Expectation(request("three")).thenRespond(response("three"))
        );
        String renderedList = "" +
            "  \"activeExpectations\" : [ {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(0).getId() + ":     one\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"one\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(0).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(1).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(1).getId() + ":     two\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"two\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(1).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"key\" : \"" + expectations.get(2).getId() + "\"," + NEW_LINE +
            "    \"description\" : \"" + expectations.get(2).getId() + ":   three\"," + NEW_LINE +
            "    \"value\" : {" + NEW_LINE +
            "      \"httpRequest\" : {" + NEW_LINE +
            "        \"path\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"httpResponse\" : {" + NEW_LINE +
            "        \"statusCode\" : 200," + NEW_LINE +
            "        \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "        \"body\" : \"three\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"id\" : \"" + expectations.get(2).getId() + "\"," + NEW_LINE +
            "      \"priority\" : 0," + NEW_LINE +
            "      \"timeToLive\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"times\" : {" + NEW_LINE +
            "        \"unlimited\" : true" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // then
        shouldRenderFilteredLogEntriesCorrectly(true, request(), Collections.emptyList(), expectations, renderedList);
    }

    // ---------------------------------------------------------------------------------------------
    // Option 6: the dashboard caches the (expensive) ExpectationDTO -> JSON serialisation and reuses
    // it while the expectation is unchanged. Two properties are proved:
    //   (1) THE WIN, race-immune: once the cache is warm, repeatedly updating an UNCHANGED set
    //       re-serialises nothing (serialisation-count delta == 0), and the emitted JSON is identical.
    //   (2) THE SAFETY, conservativeness: an added / edited / removed / Times-consumed expectation is
    //       reflected in the emitted JSON — the cache never shows a stale expectation. It is backed by
    //       a count assertion that the change DID cause a re-serialisation.
    // The observable signal is activeExpectationSerialisationCountForTesting(): it counts only genuine
    // (cache-miss) serialisations. Absolute totals are NOT asserted because, on a COLD cache, two
    // concurrent initial update passes can each populate it once (a harmless, bounded startup race);
    // the tests therefore quiesce first, then assert DELTAS, which are unaffected by that race because
    // a warm cache yields hits on every thread.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotReserialiseUnchangedActiveExpectations() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when the dashboard is updated repeatedly with nothing changed
        String first = awaitFrame(fixture, request());
        String second = awaitFrame(fixture, request());
        String third = awaitFrame(fixture, request());

        // then nothing is re-serialised and every frame is byte-identical
        assertThat("no re-serialisation for an unchanged set", fixture.handler.activeExpectationSerialisationCountForTesting(), is(warm));
        assertThat(second, is(first));
        assertThat(third, is(first));
    }

    @Test
    public void shouldReserialiseOnlyTheAddedExpectation() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when a fourth expectation is added
        fixture.requestMatchers.add(new Expectation(request("four")).withId("id-four").thenRespond(response("four")), MockServerMatcherNotifier.Cause.API);
        long afterAdd = quiesce(fixture.handler);

        // then the new expectation appears, and exactly one further serialisation occurred (the three
        // pre-existing expectations were reused)
        String frame = awaitFrame(fixture, request());
        assertThat(frame, containsString("id-four"));
        assertThat("only the added expectation is serialised", afterAdd - warm, is(1L));
    }

    @Test
    public void shouldReserialiseEditedExpectationAndReflectNewContent() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("original-two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when the middle expectation is edited in place (same id, changed response body)
        fixture.requestMatchers.add(new Expectation(request("two")).withId("id-two").thenRespond(response("edited-two")), MockServerMatcherNotifier.Cause.API);
        long afterEdit = quiesce(fixture.handler);

        // then the NEW body is shown (never the stale cached one) and exactly one re-serialisation
        // occurred
        String frame = awaitFrame(fixture, request());
        assertThat(frame, containsString("edited-two"));
        assertThat(frame, not(containsString("original-two")));
        assertThat("only the edited expectation is re-serialised", afterEdit - warm, is(1L));
    }

    @Test
    public void shouldNotReserialiseWhenExpectationRemoved() throws InterruptedException {
        Fixture fixture = newFixture(Arrays.asList(
            new Expectation(request("one")).withId("id-one").thenRespond(response("one")),
            new Expectation(request("two")).withId("id-two").thenRespond(response("two")),
            new Expectation(request("three")).withId("id-three").thenRespond(response("three"))
        ));
        long warm = quiesce(fixture.handler);

        // when one expectation is removed
        fixture.requestMatchers.clear(request("two"));
        long afterRemove = quiesce(fixture.handler);

        // then the removed expectation is gone, the survivors remain, and nothing was re-serialised
        String frame = awaitFrame(fixture, request());
        assertThat(frame, containsString("id-one"));
        assertThat(frame, containsString("id-three"));
        assertThat(frame, not(containsString("id-two")));
        assertThat("removal re-serialises nothing", afterRemove - warm, is(0L));
    }

    @Test
    public void shouldReserialiseWhenTimesConsumedOnServingPath() throws InterruptedException {
        // given one limited-Times expectation, warm in the cache with remainingTimes == 2
        Fixture fixture = newFixture(Collections.singletonList(
            new Expectation(request("once"), Times.exactly(2), TimeToLive.unlimited(), 0).withId("id-once").thenRespond(response("body"))
        ));
        long warm = quiesce(fixture.handler);
        assertThat(awaitFrame(fixture, request()), containsString("\"remainingTimes\" : 2"));

        // when the SERVING path consumes one match (remainingTimes 2 -> 1). This is the trap: the
        // control-plane modification counter does not move, but the serialised form did.
        fixture.requestMatchers.firstMatchingExpectation(request("once"));
        long afterConsume = quiesce(fixture.handler);

        // then the dashboard shows the NEW remaining count, not the stale cached "2", and the
        // expectation was re-serialised
        String frame = awaitFrame(fixture, request());
        assertThat(frame, containsString("\"remainingTimes\" : 1"));
        assertThat(frame, not(containsString("\"remainingTimes\" : 2")));
        assertThat("consuming Times re-serialises the expectation", afterConsume - warm, is(1L));
    }

    @Test
    public void shouldCapActiveExpectationsAtLimitAndCacheThem() throws InterruptedException {
        // given 150 expectations (above the 100 UI_UPDATE_ITEM_LIMIT)
        List<Expectation> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new Expectation(request("/path" + i)).withId(String.format("id-%03d", i)).thenRespond(response("body" + i)));
        }
        Fixture fixture = newFixture(many);
        long warm = quiesce(fixture.handler);

        // then only the capped number of expectations is emitted, and a second unchanged update
        // re-serialises none of them (the cap is applied before serialisation, and the cached trees
        // are reused across updates)
        String frame = awaitFrame(fixture, request());
        assertThat("activeExpectations capped at UI_UPDATE_ITEM_LIMIT", countOccurrences(frame, "\"key\" : \"id-"), is(100));
        awaitFrame(fixture, request());
        assertThat("a warm capped set re-serialises nothing", fixture.handler.activeExpectationSerialisationCountForTesting(), is(warm));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    // Minimal fixture: seed expectations BEFORE the handler is registered (so seeding does not itself
    // serialise), then register a single live-view connection.
    private static final class Fixture {
        private final RequestMatchers requestMatchers;
        private final DashboardWebSocketHandler handler;
        private final MockChannelHandlerContext ctx;

        private Fixture(RequestMatchers requestMatchers, DashboardWebSocketHandler handler, MockChannelHandlerContext ctx) {
            this.requestMatchers = requestMatchers;
            this.handler = handler;
            this.ctx = ctx;
        }
    }

    private Fixture newFixture(List<Expectation> initial) {
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketHandlerTest.class);
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        HttpState httpState = new HttpState(configuration(), mockServerLogger, scheduler);
        RequestMatchers requestMatchers = httpState.getRequestMatchers();
        if (!initial.isEmpty()) {
            requestMatchers.update(initial.toArray(new Expectation[0]), MockServerMatcherNotifier.Cause.API);
        }
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, true).registerListeners();
        MockChannelHandlerContext ctx = new MockChannelHandlerContext();
        handler.getClientRegistry().put(ctx, request());
        return new Fixture(requestMatchers, handler, ctx);
    }

    // Drive updates until the serialisation counter stops moving for a settle window, then return the
    // settled value. This warms the cache and absorbs the cold-start double-fire race, so that a
    // subsequent DELTA measures only what the operation under test caused.
    private long quiesce(DashboardWebSocketHandler handler) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        long previous = -1;
        while (System.currentTimeMillis() < deadline) {
            long current = handler.activeExpectationSerialisationCountForTesting();
            if (current == previous) {
                return current;
            }
            previous = current;
            Thread.sleep(700);
        }
        throw new AssertionError("serialisation count did not settle");
    }

    // Drive one update and return the resulting frame text. The dashboard throttles sends to roughly
    // one per second, so retry until a fresh frame is produced.
    private String awaitFrame(Fixture fixture, RequestDefinition filter) throws InterruptedException {
        fixture.ctx.textWebSocketFrame = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            fixture.handler.sendUpdate(fixture.ctx, filter);
            Thread.sleep(400);
            if (fixture.ctx.textWebSocketFrame != null) {
                return fixture.ctx.textWebSocketFrame.text();
            }
        }
        throw new AssertionError("no dashboard frame produced within timeout");
    }

    private void shouldRenderFilteredLogEntriesCorrectly(boolean contains, RequestDefinition requestFilter, List<LogEntry> logEntries, List<Expectation> expectations, String... renderListSections) throws InterruptedException {
        // given
        MockServerLogger mockServerLogger = new MockServerLogger(DashboardWebSocketHandlerTest.class);
        Scheduler scheduler = new Scheduler(configuration(), mockServerLogger, true);
        HttpState httpState = new HttpState(configuration(), mockServerLogger, scheduler);
        new Scheduler.SchedulerThreadFactory("MockServer Test " + this.getClass().getSimpleName()).newThread(() -> {
            MockServerEventLog mockServerEventLog = httpState.getMockServerLog();
            for (LogEntry logEntry : logEntries) {
                mockServerEventLog.add(logEntry);
            }
            RequestMatchers requestMatchers = httpState.getRequestMatchers();
            if (!expectations.isEmpty()) {
                requestMatchers.update(expectations.toArray(new Expectation[0]), MockServerMatcherNotifier.Cause.API);
            }
        }).start();
        SECONDS.sleep(1);
        DashboardWebSocketHandler handler =
            new DashboardWebSocketHandler(httpState, false, true)
                .registerListeners();
        MockChannelHandlerContext mockChannelHandlerContext = new MockChannelHandlerContext();
        handler.getClientRegistry().put(mockChannelHandlerContext, request());

        // when
        handler.sendUpdate(mockChannelHandlerContext, requestFilter);
        SECONDS.sleep(1);

        // then
        TextWebSocketFrame textWebSocketFrame = mockChannelHandlerContext.textWebSocketFrame;
        for (String renderListSection : renderListSections) {
            assertThat(textWebSocketFrame.text(), contains ? containsString(renderListSection) : is(renderListSection));
        }
    }

    @Test
    public void shouldRejectWebSocketUpgradeOverHttp2() {
        // given
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), new Scheduler(configuration(), new MockServerLogger()));
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, true, false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        http2Enabled(channel);
        DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/_mockserver_ui_websocket"
        );

        // when
        channel.writeInbound(upgradeRequest);

        // then
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(501));
    }

    private static DefaultFullHttpRequest webSocketUpgradeRequest() {
        DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/_mockserver_ui_websocket"
        );
        upgradeRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        upgradeRequest.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        upgradeRequest.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        upgradeRequest.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        upgradeRequest.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return upgradeRequest;
    }

    @Test
    public void shouldRejectWebSocketUpgradeWhenControlPlaneAuthEnabledAndNotAuthenticated() {
        // given - control-plane auth configured but the upgrade carries no/invalid credentials
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), new Scheduler(configuration(), new MockServerLogger()));
        httpState.setControlPlaneAuthenticationHandler(request -> false);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - 401 and the channel is NOT upgraded to a web socket
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(401));
    }

    @Test
    public void shouldAllowWebSocketUpgradeWhenControlPlaneAuthEnabledAndAuthenticated() {
        // given - control-plane auth configured and the upgrade is authenticated
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), new Scheduler(configuration(), new MockServerLogger()));
        httpState.setControlPlaneAuthenticationHandler(request -> true);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - the gate allowed the upgrade so NO 401/403 rejection is written. The real
        // WebSocket handshake needs an HTTP codec on the pipeline (present in production, absent
        // in this bare EmbeddedChannel), so it writes no capturable 101 here — the security-
        // relevant assertion is the absence of a rejection (contrast the reject test below).
        assertNotRejected(channel.readOutbound());
    }

    @Test
    public void shouldAllowWebSocketUpgradeWhenNoControlPlaneAuthConfigured() {
        // given - default config: NO control-plane auth handler set, so the dashboard stays open
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), new Scheduler(configuration(), new MockServerLogger()));
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // when
        channel.writeInbound(webSocketUpgradeRequest());

        // then - non-breaking default: no credentials required, no rejection written
        assertNotRejected(channel.readOutbound());
    }

    private static void assertNotRejected(FullHttpResponse response) {
        // The gate allowed the upgrade: either the handshake proceeded (101 Switching Protocols)
        // or nothing capturable was written in this bare channel. Either way it must NOT be a
        // 401/403 auth challenge.
        if (response != null) {
            assertThat(response.status().code(), not(anyOf(is(401), is(403))));
        }
    }

    public static class MockChannelHandlerContext extends EmbeddedChannel {

        // can't use future as called multiple times
        TextWebSocketFrame textWebSocketFrame;

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof TextWebSocketFrame) {
                textWebSocketFrame = (TextWebSocketFrame) msg;
            }
            return null;
        }
    }

}
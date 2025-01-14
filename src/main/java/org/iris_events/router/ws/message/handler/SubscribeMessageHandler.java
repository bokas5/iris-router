package org.iris_events.router.ws.message.handler;

import static org.iris_events.router.ws.message.handler.SubscribeMessageHandler.EVENT_NAME;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.iris_events.router.events.ErrorEvent;
import org.iris_events.router.events.UserAuthenticated;
import org.iris_events.router.events.UserAuthenticatedEvent;
import org.iris_events.router.model.RequestWrapper;
import org.iris_events.router.model.Subscribe;
import org.iris_events.router.model.UserSession;
import org.iris_events.router.model.sub.SubscribeInternal;
import org.iris_events.router.service.BackendService;
import org.iris_events.router.service.WebsocketRegistry;
import org.iris_events.common.ErrorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@ApplicationScoped
@Named(EVENT_NAME)
public class SubscribeMessageHandler implements MessageHandler {

    public static final String EVENT_NAME = "subscribe";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WebsocketRegistry websocketRegistry;

    @Inject
    BackendService backendService;

    @Override
    public void handle(UserSession userSession, RequestWrapper requestWrapper) {
        final var payload = requestWrapper.payload();
        final var subscribe = objectMapper.convertValue(payload, Subscribe.class);
        final var clientTraceId = requestWrapper.clientTraceId();
        subscribe(userSession, subscribe, clientTraceId);
    }

    private void subscribe(final UserSession userSession, final Subscribe subscribe, final String clientTraceId) {
        if (subscribe.getToken() != null) {
            final var loginSucceeded = websocketRegistry.login(userSession, subscribe.getToken());
            if (loginSucceeded) {
                final var userAuthenticatedEvent = new UserAuthenticatedEvent();
                userSession.sendEvent(userAuthenticatedEvent, clientTraceId);
                final var userAuthenticated = new UserAuthenticated(userSession.getUserId());
                // TODO: do not emit yet, we need to declare queue first and add correlationId to the me
                // sendIrisEventToBackend(userSession, clientTraceId, userAuthenticated);
            } else {
                final var errorEvent = new ErrorEvent(ErrorType.AUTHENTICATION_FAILED, ErrorEvent.AUTHORIZATION_FAILED_CLIENT_CODE,
                        "authorization failed");
                userSession.sendEvent(errorEvent, clientTraceId);
                // when token is present, login must succeed
                return;
            }
        } else {
            if (!userSession.isValid()) {
                userSession.sendSessionInvalidError(clientTraceId);
                return;
            }
        }
        if (subscribe.getHeartbeat() != null) {
            userSession.setSendHeartbeat(subscribe.getHeartbeat());
        }

        subscribeResources(userSession, subscribe, clientTraceId);
    }

    private void subscribeResources(final UserSession userSession, final Subscribe subscribe, final String clientTraceId) {
        final var resourceSubscriptions = subscribe.getResources();
        if (resourceSubscriptions == null) {
            return;
        }

        // create new subscription service specific event to omit token
        resourceSubscriptions.forEach(subscription -> {
            var resourceId = subscription.resourceId();
            var resourceType = subscription.resourceType();

            final var resourceSubscriptionInternalEvent = new SubscribeInternal(resourceType, resourceId);
            backendService.sendInternalEvent(userSession, clientTraceId, resourceSubscriptionInternalEvent);
        });

    }
}

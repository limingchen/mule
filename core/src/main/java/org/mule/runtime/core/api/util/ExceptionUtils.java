/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api.util;

import static java.lang.System.lineSeparator;
import static java.util.Arrays.stream;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mule.runtime.api.exception.ExceptionHelper.getExceptionsAsList;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.context.notification.EnrichedNotificationInfo.createInfo;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.UNKNOWN;
import static org.mule.runtime.core.api.exception.Errors.Identifiers.UNKNOWN_ERROR_IDENTIFIER;
import static org.mule.runtime.core.component.ComponentAnnotations.ANNOTATION_NAME;
import static org.mule.runtime.core.internal.exception.ErrorMapping.ANNOTATION_ERROR_MAPPINGS;

import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.api.meta.AnnotatedObject;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.notification.EnrichedNotificationInfo;
import org.mule.runtime.core.api.exception.ErrorTypeLocator;
import org.mule.runtime.core.api.exception.ErrorTypeRepository;
import org.mule.runtime.core.api.exception.MessagingException;
import org.mule.runtime.core.api.exception.SingleErrorTypeMatcher;
import org.mule.runtime.core.api.exception.TypedException;
import org.mule.runtime.core.api.exception.WrapperErrorMessageAwareException;
import org.mule.runtime.core.api.execution.ExceptionContextProvider;
import org.mule.runtime.core.api.message.ErrorBuilder;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.internal.exception.ErrorMapping;
import org.slf4j.Logger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Mule exception utilities.
 */
public class ExceptionUtils {

  /**
   * This method returns true if the throwable contains a {@link Throwable} that matches the specified class or subclass in the
   * exception chain. Subclasses of the specified class do match.
   *
   * @param throwable the throwable to inspect, may be null
   * @param type      the type to search for, subclasses match, null returns false
   * @return the index into the throwable chain, false if no match or null input
   */
  public static boolean containsType(Throwable throwable, Class<?> type) {
    return org.apache.commons.lang3.exception.ExceptionUtils.indexOfType(throwable, type) > -1;
  }

  /**
   * Similar to {@link org.apache.commons.lang3.exception.ExceptionUtils#getStackTrace(Throwable)} but removing the exception and
   * causes messages. This is useful to determine if two exceptions have matching stack traces regardless of the messages which
   * may contain invokation specific data
   *
   * @param throwable the throwable to inspect, may be <code>null</code>
   * @return the stack trace as a string, with the messages stripped out. Empty string if throwable was <code>null</code>
   */
  public static String getFullStackTraceWithoutMessages(Throwable throwable) {
    StringBuilder builder = new StringBuilder();

    for (String frame : org.apache.commons.lang3.exception.ExceptionUtils.getStackFrames(throwable)) {
      builder.append(frame.replaceAll(":\\s+([\\w\\s]*.*)", "").trim()).append(lineSeparator());
    }

    return builder.toString();
  }

  /**
   * Introspects the {@link Throwable} parameter to obtain the first {@link Throwable} of type {@link ConnectionException} in the
   * exception chain.
   *
   * @param throwable the last throwable in the exception chain.
   * @return an {@link Optional} value with the first {@link ConnectionException} in the exception chain if any.
   */
  public static Optional<ConnectionException> extractConnectionException(Throwable throwable) {
    return extractOfType(throwable, ConnectionException.class);
  }

  /**
   * Introspects the {@link Throwable} parameter to obtain the first {@link Throwable} of type {@code throwableType} in the
   * exception chain and return the cause of it.
   *
   * @param throwable     the last throwable on the exception chain.
   * @param throwableType the type of the throwable that the cause is wanted.
   * @return the cause of the first {@link Throwable} of type {@code throwableType}.
   */
  public static Optional<Throwable> extractCauseOfType(Throwable throwable, Class<? extends Throwable> throwableType) {
    Optional<? extends Throwable> typeThrowable = extractOfType(throwable, throwableType);
    return typeThrowable.map(Throwable::getCause);
  }

  /**
   * Introspects the {@link Throwable} parameter to obtain the first {@link Throwable} of type {@code throwableType} in the
   * exception chain.
   * <p>
   * This method handles recursive cause structures that might otherwise cause infinite loops. If the throwable parameter is a
   * {@link ConnectionException} the same value will be returned. If the throwable parameter has a cause of itself, then an empty
   * value will be returned.
   *
   * @param throwable     the last throwable on the exception chain.
   * @param throwableType the type of the throwable is wanted to find.
   * @return the cause of the first {@link Throwable} of type {@code throwableType}.
   */
  @SuppressWarnings("unchecked")
  public static <T extends Throwable> Optional<T> extractOfType(Throwable throwable, Class<T> throwableType) {
    if (throwable == null || !containsType(throwable, throwableType)) {
      return empty();
    }

    return (Optional<T>) stream(org.apache.commons.lang3.exception.ExceptionUtils.getThrowables(throwable))
        .filter(throwableType::isInstance).findFirst();
  }

  /**
   * Executes the given {@code callable} knowing that it might throw an {@link Exception} of type {@code expectedExceptionType}.
   * If that happens, then it will re throw such exception.
   * <p>
   * If the {@code callable} throws a {@link RuntimeException} of a different type, then it is also re-thrown. Finally, if an
   * exception of any different type is thrown, then it is handled by delegating into the {@code exceptionHandler}, which might in
   * turn also throw an exception or handle it returning a value.
   *
   * @param expectedExceptionType the type of exception which is expected to be thrown
   * @param callable              the delegate to be executed
   * @param exceptionHandler      a {@link ExceptionHandler} in case an unexpected exception is found instead
   * @param <T>                   the generic type of the return value
   * @param <E>                   the generic type of the expected exception
   * @return a value returned by either the {@code callable} or the {@code exceptionHandler}
   * @throws E if the expected exception is actually thrown
   */
  public static <T, E extends Exception> T tryExpecting(Class<E> expectedExceptionType,
                                                        Callable<T> callable,
                                                        ExceptionHandler<T, E> exceptionHandler) throws E {
    try {
      return callable.call();
    } catch (Exception e) {
      if (expectedExceptionType.isInstance(e)) {
        throw (E) e;
      }

      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }

      return exceptionHandler.handle(e);
    }
  }

  /**
   * Create new {@link Event} with {@link org.mule.runtime.api.message.Error} instance set.
   *
   * @param currentEvent       event when error occured.
   * @param processor    message processor/source.
   * @param messagingException messaging exception.
   * @param errorTypeLocator   the mule context.
   * @return new {@link Event} with relevant {@link org.mule.runtime.api.message.Error} set.
   */
  public static Event createErrorEvent(Event currentEvent,
                                       Object processor,
                                       MessagingException messagingException,
                                       ErrorTypeLocator errorTypeLocator) {
    // TODO: MULE-10970/MULE-10971 - Change signature to AnnotatedObject once every processor and source is one
    Throwable causeException = messagingException.getCause() != null ? messagingException.getCause() : messagingException;

    boolean errorMatchesException = messagingException.getEvent().getError()
        .filter(error -> errorCauseMatchesException(causeException, error))
        .filter(error -> messagingException.causedExactlyBy(error.getCause().getClass()))
        .isPresent();

    if (hasErrorMappings(processor) || !errorMatchesException) {
      Error newError = getErrorFromFailingProcessor(processor, causeException, currentEvent, errorTypeLocator);
      Event event = Event.builder(messagingException.getEvent()).error(newError).build();
      messagingException.setProcessedEvent(event);
      return event;
    } else {
      return currentEvent;
    }
  }

  /**
   * Updates the {@link MessagingException} to be thrown based on the content of the {@code exception} parameter and the chain of
   * causes inside it.
   *
   * @param logger              instance to use for logging
   * @param processor           the failing processor
   * @param exception           the exception to update based on it's content
   * @param errorTypeLocator    the error type locator
   * @param errorTypeRepository the error type repository
   * @param muleContext         the context of the artifact
   * @return a {@link MessagingException} with the proper {@link Error} associated to it's {@link Event}
   */
  public static MessagingException updateMessagingException(Logger logger,
                                                            Processor processor,
                                                            MessagingException exception,
                                                            ErrorTypeLocator errorTypeLocator,
                                                            ErrorTypeRepository errorTypeRepository,
                                                            MuleContext muleContext) {
    Optional<Exception> root = findRootExceptionForErrorHandling(exception, processor, errorTypeLocator, errorTypeRepository);

    if (root.isPresent()) {
      if (logger.isDebugEnabled()) {
        logger.debug("discarding exception that is wrapping the original error", exception);
      }
      if (!hasErrorMappings(processor) && isWellFormedMessagingException(root.get())) {
        return (MessagingException) root.get();
      }
    }

    Exception resultRootException = root.orElse(exception);
    Processor failing = exception.getFailingMessageProcessor();
    if (failing == null && resultRootException instanceof MessagingException) {
      failing = ((MessagingException) resultRootException).getFailingMessageProcessor();
    }

    if (failing == null) {
      failing = processor;
      exception = new MessagingException(createStaticMessage(resultRootException.getMessage()), exception.getEvent(),
                                         unwrapMessagingException(resultRootException), processor);
    }

    Error error = getErrorFromFailingProcessor(processor, resultRootException, exception.getEvent(), errorTypeLocator);
    Event resultEvent = Event.builder(exception.getEvent()).error(error).build();
    exception.setProcessedEvent(createErrorEvent(resultEvent, processor, exception, errorTypeLocator));
    return putContext(exception, failing, exception.getEvent(), muleContext);
  }

  /**
   * Determines the {@link Error} of a given exception thrown by a given message processor.
   *
   * @param object the component that threw the exception.
   * @param cause    the exception thrown.
   * @param currentEvent the current event
   * @param locator the {@link ErrorTypeLocator}
   * @return a resolved {@link Error} for the processor
   */
  public static Error getErrorFromFailingProcessor(Object object, Throwable cause, Event currentEvent, ErrorTypeLocator locator) {
    ErrorType currentError = currentEvent != null ? currentEvent.getError().map(Error::getErrorType).orElse(null) : null;
    currentError = hasKnownError(locator, cause) ? locator.lookupErrorType(cause) : currentError;
    return ErrorBuilder.builder(cause).errorType(getErrorTypeFromFailingProcessor(object, cause, currentError, locator)).build();
  }

  private static ErrorType getErrorTypeFromFailingProcessor(Object annotatedObject, Throwable exception,
                                                            ErrorType currentErrorType, ErrorTypeLocator locator) {
    Throwable causeException = unwrapWrapperErrorMessageAwareException(exception);
    ComponentIdentifier componentIdentifier = getComponentIdentifier(annotatedObject);
    List<ErrorMapping> errorMappings = getErrorMappings(annotatedObject);

    ErrorType errorType;
    if (currentErrorType != null) {
      errorType = currentErrorType;
    } else if (componentIdentifier != null) {
      errorType = locator.lookupComponentErrorType(componentIdentifier, causeException);
    } else {
      errorType = locator.lookupErrorType(causeException);
    }

    if (errorMappings != null && !errorMappings.isEmpty()) {
      Optional<ErrorMapping> matchedErrorMapping = errorMappings.stream().filter(mapping -> mapping.match(errorType)).findFirst();
      if (matchedErrorMapping.isPresent()) {
        return matchedErrorMapping.get().getTarget();
      }
    }
    return errorType;
  }

  /**
   * Searches for the root {@link Exception} to use to generate the {@link Error} inside the {@link Event}.
   * <p>
   * If such exception exists, then it's because the exception is wrapping an exception that already has an error. For instance, a
   * streaming error. Or it may also be that there's a wrapper but just for throwing a more specific for details exception.
   * <p>
   * If there's already a {@link MessagingException} with an {@link Event} that contains a non empty {@link Error} then that
   * exception will be returned since it means that the whole process of creating the error was already executed.
   *
   * @param exception           the exception to search in all it's causes for a {@link MessagingException} with an {@link Error}
   * @param processor           the processor that thrown the exception
   * @param errorTypeLocator    the locator to discover {@link ErrorType}s
   * @param errorTypeRepository the error type repository
   * @return the found exception or empty.
   */
  private static Optional<Exception> findRootExceptionForErrorHandling(Exception exception,
                                                                       Processor processor,
                                                                       ErrorTypeLocator errorTypeLocator,
                                                                       ErrorTypeRepository errorTypeRepository) {
    List<Throwable> causesAsList = getExceptionsAsList(exception);
    for (Throwable cause : causesAsList) {
      if (hasKnownError(errorTypeLocator, cause) || isWellFormedMessagingException(cause)) {
        return of((Exception) cause);
      }
    }
    int causeIndex = 0;
    for (Throwable cause : causesAsList) {
      if (cause instanceof TypedException) {
        return of((Exception) cause);
      }
      if (cause instanceof MuleException || cause instanceof MuleRuntimeException) {
        ErrorType errorType = errorTypeLocator.lookupErrorType(cause);
        ErrorType unknownErrorType = errorTypeRepository.getErrorType(UNKNOWN).get();
        if (!errorType.equals(unknownErrorType)) {
          // search for a more specific wrapper first
          int nextCauseIndex = causeIndex + 1;
          ComponentLocation componentLocation =
              processor instanceof AnnotatedObject ? ((AnnotatedObject) processor).getLocation() : null;
          if (causesAsList.size() > nextCauseIndex && componentLocation != null) {
            Throwable causeOwnerException = causesAsList.get(nextCauseIndex);
            ErrorType causeOwnerErrorType;
            if (causeOwnerException instanceof TypedException) {
              causeOwnerErrorType = ((TypedException) causeOwnerException).getErrorType();
            } else {
              causeOwnerErrorType = errorTypeLocator
                  .lookupComponentErrorType(componentLocation.getComponentIdentifier().getIdentifier(),
                                            causeOwnerException);
            }
            if (!unknownErrorType.equals(causeOwnerErrorType)
                && new SingleErrorTypeMatcher(errorType).match(causeOwnerErrorType)) {
              return of((Exception) causeOwnerException);
            }
          }
          return of((Exception) cause);
        }
      }
      causeIndex++;
    }
    return empty();
  }

  private static boolean hasKnownError(ErrorTypeLocator errorTypeLocator, Throwable cause) {
    return !errorTypeLocator.lookupErrorType(cause).getIdentifier().equals(UNKNOWN_ERROR_IDENTIFIER);
  }

  private static boolean errorCauseMatchesException(Throwable causeException, Error error) {
    return causeException.equals(error.getCause());
  }

  public static MessagingException updateMessagingExceptionWithError(MessagingException me, Processor failing, MuleContext ctx) {
    // If Event already has Error, for example because of an interceptor then conserve existing Error instance
    if (!me.getEvent().getError().isPresent()) {
      me.setProcessedEvent(createErrorEvent(me.getEvent(), failing, me, ctx.getErrorTypeLocator()));
    }
    return putContext(me, failing, me.getEvent(), ctx);
  }

  private static Throwable unwrapWrapperErrorMessageAwareException(Throwable exception) {
    return exception instanceof WrapperErrorMessageAwareException ?
             ((WrapperErrorMessageAwareException) exception).getRootCause() : exception;
  }

  private static MessagingException putContext(MessagingException me, Processor failing, Event event, MuleContext context) {
    EnrichedNotificationInfo notificationInfo = createInfo(event, me, null);
    for (ExceptionContextProvider exceptionContextProvider : context.getExceptionContextProviders()) {
      exceptionContextProvider.getContextInfo(notificationInfo, failing).forEach((k, v) -> {
        if (!me.getInfo().containsKey(k)) {
          me.getInfo().put(k, v);
        }
      });
    }
    return me;
  }

  private static List<ErrorMapping> getErrorMappings(Object o) {
    return isAnnotatedObject(o) ? (List<ErrorMapping>) ((AnnotatedObject) o).getAnnotation(ANNOTATION_ERROR_MAPPINGS) : null;
  }

  private static ComponentIdentifier getComponentIdentifier(Object o) {
    return isAnnotatedObject(o) ? (ComponentIdentifier) ((AnnotatedObject) o).getAnnotation(ANNOTATION_NAME): null;
  }

  private static boolean isAnnotatedObject(Object annotatedObject) {
    return AnnotatedObject.class.isAssignableFrom(annotatedObject.getClass());
  }

  private static Throwable unwrapMessagingException(Exception exception) {
    return exception instanceof MessagingException ? exception.getCause() : exception;
  }

  private static boolean hasErrorMappings(Object processor) {
    boolean hasErrorMappings = false;
    if (isAnnotatedObject(processor)) {
      final Object errorMappingAnnotation = ((AnnotatedObject) processor).getAnnotation(ANNOTATION_ERROR_MAPPINGS);
      hasErrorMappings = errorMappingAnnotation != null && !((List<ErrorMapping>) errorMappingAnnotation).isEmpty();
    }
    return hasErrorMappings;
  }

  private static boolean isWellFormedMessagingException(Throwable t) {
    return t instanceof MessagingException
             && ((MessagingException) t).getEvent().getError().isPresent()
             && ((MessagingException) t).getFailingMessageProcessor() != null;
  }
}

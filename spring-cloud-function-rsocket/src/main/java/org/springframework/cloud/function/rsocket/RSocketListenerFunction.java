/*
 * Copyright 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.rsocket;

import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Function;

import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.util.DefaultPayload;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.json.JsonMapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.CollectionUtils;

/**
 * Wrapper over an instance of target Function (represented by {@link FunctionInvocationWrapper})
 * which will use the result of the invocation of such function as an input to another RSocket
 * effectively composing two functions over RSocket.
 *
 * @author Oleg Zhurakousky
 * @since 3.1
 */
class RSocketListenerFunction implements Function<Message<byte[]>, Publisher<Message<byte[]>>> {

	private static String splash = "   ____         _             _______             __  ____              __  _             ___  ____         __       __ \n" +
			"  / __/__  ____(_)__  ___ _  / ___/ /__  __ _____/ / / __/_ _____  ____/ /_(_)__  ___    / _ \\/ __/__  ____/ /_____ / /_\n" +
			" _\\ \\/ _ \\/ __/ / _ \\/ _ `/ / /__/ / _ \\/ // / _  / / _// // / _ \\/ __/ __/ / _ \\/ _ \\  / , _/\\ \\/ _ \\/ __/  '_/ -_) __/\n" +
			"/___/ .__/_/ /_/_//_/\\_, /  \\___/_/\\___/\\_,_/\\_,_/ /_/  \\_,_/_//_/\\__/\\__/_/\\___/_//_/ /_/|_/___/\\___/\\__/_/\\_\\\\__/\\__/ \n" +
			"   /_/              /___/                                                                                               \n" +
			"";

	private static Log logger = LogFactory.getLog(RSocketListenerFunction.class);

	private final FunctionInvocationWrapper targetFunction;

	private RSocket rsocket;

	private final JsonMapper jsonMapper;

	RSocketListenerFunction(FunctionInvocationWrapper targetFunction, JsonMapper jsonMapper) {
		this.targetFunction = targetFunction;
		this.jsonMapper = jsonMapper;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Publisher<Message<byte[]>> apply(Message<byte[]> input) {
		if (logger.isDebugEnabled()) {
			logger.debug("Executiing: " + this.targetFunction);
		}

		Object rawResult = this.targetFunction.apply(input);
		return rawResult instanceof Publisher ? (Publisher<Message<byte[]>>) rawResult : Mono.just((Message<byte[]>) rawResult);
	}

	public RSocket getRsocket() {
		if (this.rsocket == null) {
			Type functionType = this.targetFunction.getFunctionType();

			if (this.rsocket == null) {
				this.rsocket = this.buildRSocket(this.targetFunction, functionType, this);
			}
			this.printSplashScreen(this.targetFunction.getFunctionDefinition(), functionType);
		}
		return this.rsocket;
	}

	private RSocket buildRSocket(FunctionInvocationWrapper targetFunction, Type functionType, Function<Message<byte[]>, Publisher<Message<byte[]>>> function) {
		String definition = targetFunction.getFunctionDefinition();
		RSocket clientRSocket = new RSocket() { // imperative function or Function<?, Mono> = requestResponse
			@Override
			public Mono<Payload> requestResponse(Payload payload) {
				if (logger.isDebugEnabled()) {
					logger.debug("Invoking function '" + definition + "' as RSocket `requestResponse`.");
				}

				if (isFunctionReactive(functionType)) {
					Flux<Payload> result = this.requestChannel(Flux.just(payload));
					return Mono.from(result);
				}
				else {
					Message<byte[]> inputMessage = deserealizePayload(payload);
					Mono<Message<byte[]>> result = Mono.from(function.apply(inputMessage));
					return result.map(message -> DefaultPayload.create(message.getPayload(), jsonMapper.toJson(message.getHeaders())));
				}
			}

			@Override
			public Flux<Payload> requestStream(Payload payload) {
				if (logger.isDebugEnabled()) {
					logger.debug("Invoking function '" + definition + "' as RSocket `requestStream`.");
				}
				if (isFunctionReactive(functionType)) {
					return this.requestChannel(Flux.just(payload));
				}
				else {
					Message<byte[]> inputMessage = deserealizePayload(payload);
					Flux<Message<byte[]>> result = Flux.from(function.apply(inputMessage));
					return result.map(message -> DefaultPayload.create(message.getPayload()));
				}
			}

			@SuppressWarnings({ "unchecked", "rawtypes" })
			@Override
			public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
				if (logger.isDebugEnabled()) {
					logger.debug("Invoking function '" + definition + "' as RSocket `requestChannel`.");
				}
				if (isFunctionReactive(functionType)) {
					return Flux.from(payloads)
							.transform(inputFlux -> inputFlux.map(payload -> deserealizePayload(payload)))
							.transform((Function) targetFunction)
							.transform(outputFlux -> ((Flux<Message<byte[]>>) outputFlux).map(message -> DefaultPayload.create(message.getPayload())));
				}
				else {
					return Flux.from(payloads)
							.transform(flux -> {
								return flux.flatMap(payload -> {
									Message<byte[]> inputMessage = deserealizePayload(payload);
									Flux<Message<byte[]>> result = Flux.from(function.apply(inputMessage));
									return result;
								});
							})
							.doOnNext(System.out::println)
							.transform(outputFlux -> outputFlux.map(message -> DefaultPayload.create(message.getPayload())));
				}

			}
		};
		return clientRSocket;
	}

	private static boolean isFunctionReactive(Type functionType) {
		Type inputType = FunctionTypeUtils.getInputType(functionType, 0);
		Type outputType = FunctionTypeUtils.getOutputType(functionType, 0);
		return FunctionTypeUtils.isPublisher(inputType) && FunctionTypeUtils.isFlux(outputType);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Message<byte[]> deserealizePayload(Payload payload) {
		ByteBuffer buffer = payload.getData();
		byte[] rawData = new byte[buffer.remaining()];
		buffer.get(rawData);
		Map<String, Object> headers = null;
		if (payload.hasMetadata()) {
			try {
				ByteBuffer metadata = payload.getMetadata();
				byte[] metadataBytes = new byte[metadata.remaining()];
				metadata.get(metadataBytes);
				headers = this.jsonMapper.fromJson(metadataBytes, Map.class);
			}
			catch (Exception e) {
				//throw new IllegalStateException(e);
				logger.warn("Failed to extract headers from metadata", e);
			}
		}
		MessageBuilder builder =  MessageBuilder.withPayload(rawData);
		if (!CollectionUtils.isEmpty(headers)) {
			builder.copyHeaders(headers);
		}
		Message<byte[]> inputMessage = builder.build();
		return inputMessage;

	}

	private void printSplashScreen(String definition, Type type) {
		System.out.println(splash);
		System.out.println("Function Definition: " + definition + "; T[" + type + "]");
		System.out.println("======================================================\n");
	}

}

package org.springframework.cloud.function.json;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JsonMapperTest {
	@Test
	public void objectNode_isJsonStringRepresentsCollection() {
		ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("id", "1234ab");
		node.put("foo", "bar");

		/*
		 * Passing the ObjectNode directly results in a positive identification as
		 * a collection, as its distant parent JsonNode implements Iterable.
		 */
		assertThat(JsonMapper.isJsonStringRepresentsCollection(node)).isTrue();

		String nodeAsString = node.toString();

		/*
		 * Sending the node as a string returns false, however, as the line
		 * isJsonString(value) && str.startsWith("[") && str.endsWith("]")
		 * will not be true.
		 */
		assertThat(JsonMapper.isJsonStringRepresentsCollection(nodeAsString)).isFalse();
	}
}

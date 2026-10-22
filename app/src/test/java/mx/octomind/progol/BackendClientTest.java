package mx.octomind.progol;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class BackendClientTest {

    @Test
    public void parsesAuthResponseTokens() throws Exception {
        String json = "{\"AuthenticationResult\":{"
                + "\"AccessToken\":\"access\",\"IdToken\":\"id\",\"RefreshToken\":\"refresh\"}}";
        AuthRepository.Tokens tokens = AuthRepository.parseAuthResponse(json);
        assertEquals("access", tokens.accessToken());
        assertEquals("id", tokens.idToken());
        assertEquals("refresh", tokens.refreshToken());
    }

    @Test
    public void parsesHistoryIntoContests() throws Exception {
        String json = "{\"drawType\":\"MS\",\"history\":["
                + "{\"drawType\":\"MS\",\"contestNumber\":1,\"date\":\"01/01/2020\","
                + "\"results\":[\"L\",\"E\",\"V\",\"L\",\"E\",\"V\",\"L\",\"E\",\"V\"]},"
                + "{\"drawType\":\"MS\",\"contestNumber\":2,\"date\":\"08/01/2020\","
                + "\"results\":[\"V\",\"L\",\"E\",\"V\",\"L\",\"E\",\"V\",\"L\",\"E\"]}]}";
        List<Contest> contests = ApiRepository.parseHistory(json, DrawType.MS);
        assertEquals(2, contests.size());
        assertEquals(1, contests.get(0).number());
        assertEquals(25, contests.get(0).product());
        assertEquals(9, contests.get(0).results().length);
        assertEquals("L", contests.get(0).result(0));
        assertEquals("V", contests.get(1).result(0));
    }
}

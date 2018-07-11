import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;
import java.util.UUID;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.DefaultHttpServerIODispatch;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.protocol.UriHttpAsyncRequestHandlerMapper;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.ListeningIOReactor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.EntityUtils;

import com.sun.org.apache.xml.internal.security.exceptions.Base64DecodingException;
import com.sun.org.apache.xml.internal.security.utils.Base64;

public class MafiaGameWeb {

	static private final int PORT = 80;
	static private String adminPassword = "6400";
	static private String adminSessionKey = null;

	static enum CharacterType {
		CIVILIAN("시민"), MAFIA("마피아"), DOCTOR("의사"), DETECTIVE("탐정"), SOLDIER("군인"), DEAD("유령");

		private final String charTypeName;

		CharacterType(String charTypeName) {
			this.charTypeName = charTypeName;
		}

		public String getName() {
			return charTypeName;
		}
	}

	static private final Map<CharacterType, Integer> characterQuotaMap = new HashMap<CharacterType, Integer>();

	static class Participant {
		public Participant(String appellationName) {
			this.appellationName = appellationName;
		}

		public String appellationName = null;
		public String sessionKey = null;
		
		public int wins = 0;
		
		public CharacterType oriCharType = CharacterType.CIVILIAN;
		public CharacterType charType = CharacterType.CIVILIAN;

		// 투표 정보
		public boolean done = false;

		public int voted = 0;
		public Participant savedBy = null;
		public String privateMsg = "";

		// 군인용, 게임 시작 시 한번만 초기화
		public boolean tried = false;

		public void reVote() {
			if (charType == CharacterType.DEAD)
				done = true;
			else
				done = false;

			voted = 0;
			savedBy = null;
			privateMsg = "";
		}
	}

	static private final Map<String, Participant> participantsMap = new HashMap<String, Participant>();

	static enum GameStatus {
		INITAL,
		NIGHT,
		NIGHT_FINISHED,
		DAY, 
		DAY_FINISHED, 
		GAME_FINISHED,
	}

	static class GameData {
		public GameStatus status = GameStatus.INITAL;
		public int days = 1;
		public String logMsg = "";

		public String broadMsg = null;
		public boolean civilWins = false;

		public boolean policy_startFromDay = true;
		public boolean policy_2MafiaKillsCivilan = true;
		public boolean policy_letOtherKnowWhenDie = true;		
	}

	static private final GameData gameData = new GameData();

	static private class HttpHandlerForWebVer implements HttpAsyncRequestHandler<HttpRequest> {

		public HttpHandlerForWebVer() {
			super();
		}

		public HttpAsyncRequestConsumer<HttpRequest> processRequest(final HttpRequest request, final HttpContext context) {
			// Buffer request content in memory for simplicity
			return new BasicAsyncRequestConsumer();
		}

		public void handle(final HttpRequest request, final HttpAsyncExchange httpexchange, final HttpContext context) throws HttpException, IOException {

			HttpResponse response = httpexchange.getResponse();
			handleInternal(request, response, context);
			httpexchange.submitResponse(new BasicAsyncResponseProducer(response));
		}

		private void handleInternal(final HttpRequest request, final HttpResponse response, final HttpContext context) throws HttpException, IOException {

			// Get method
			String method = request.getRequestLine().getMethod();
			int methodType = 0; // 1:GET 2:POST
			if (method.equalsIgnoreCase("GET"))
				methodType = 1;
			else if (method.equalsIgnoreCase("POST"))
				methodType = 2;
			else throw new MethodNotSupportedException(method + " method not supported");

			// Get Uri
			String url = URLDecoder.decode(request.getRequestLine().getUri(), "UTF-8");

			boolean isAdmin = false;
			String cookieAccountName = null;
			String cookieSessionKey = null;

			// Get Cookie
			Header[] cookies = request.getHeaders("Cookie");
			for (Header h : cookies) {
				String token[] = h.getValue().split("[;]");
				for (String c : token) {
					int indexOfSep = c.indexOf('=');
					String left = c.substring(0, indexOfSep).trim();
					String right = c.substring(indexOfSep + 1, c.length()).trim();
					if (left.equalsIgnoreCase("adminSessionKey")) {
						if (adminSessionKey != null && adminSessionKey.equalsIgnoreCase(right)) isAdmin = true;
					} else if (left.equalsIgnoreCase("accountName")) {
						try {
							cookieAccountName = new String(Base64.decode(right.getBytes()));
						} catch (Base64DecodingException e) {

						}
					} else if (left.equalsIgnoreCase("sessionKey")) {
						cookieSessionKey = right;
					}
				}
			}

			// 운영자 페이지 들
			if (isAdmin) {
				if (methodType == 1 && url.equals("/admin_status")) {
					String body = new String("현재 상태:" + gameData.status + "<br>");
					body += "<form action=/admin_status_post method=post>";
					body += "<input type=submit name=submit value=게임시작><br><br><input type=submit name=submit value=초기화><br></form>";
					body += "<form action=/admin_status_change_pw method=post>";
					body += "운영자 비밀번호 변경 <input type=password name=pw1 size=4> <input type=password name=pw2 size=4> <input type=submit value=변경><br></from>";
					body += "<a href=/admin>뒤로</a>";

					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("" + body + "", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					return;
				} else if (methodType == 2 && url.equals("/admin_status_change_pw")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					String pw1 = null;
					String pw2 = null;
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if(decodedEntityToken[i].startsWith("pw1=")) {
							pw1 = decodedEntityToken[i].substring("pw1=".length());
						}
						else if(decodedEntityToken[i].startsWith("pw2=")) {
							pw2 = decodedEntityToken[i].substring("pw2=".length());
						}
					}
					if(pw1 == null || pw2 == null) {
						response.setStatusCode(HttpStatus.SC_OK);
						NStringEntity nsEntity = new NStringEntity("입력 값이 없음", ContentType.create("text/html", "UTF-8"));
						response.setEntity(nsEntity);
						response.addHeader("Refresh", "2; url=/admin_status");
						return;
					}
					if(!pw1.equals(pw2)) {
						response.setStatusCode(HttpStatus.SC_OK);
						NStringEntity nsEntity = new NStringEntity("두 값이 다름", ContentType.create("text/html", "UTF-8"));
						response.setEntity(nsEntity);
						response.addHeader("Refresh", "2; url=/admin_status");
						return;
					}
					if(!pw1.equals(pw2)) {
						response.setStatusCode(HttpStatus.SC_OK);
						NStringEntity nsEntity = new NStringEntity("두 값이 다름", ContentType.create("text/html", "UTF-8"));
						response.setEntity(nsEntity);
						response.addHeader("Refresh", "2; url=/admin_status");
						return;
					}
					if(pw1.length() < 4) {
						response.setStatusCode(HttpStatus.SC_OK);
						NStringEntity nsEntity = new NStringEntity("길이가 짧음", ContentType.create("text/html", "UTF-8"));
						response.setEntity(nsEntity);
						response.addHeader("Refresh", "2; url=/admin_status");
						return;
					}
					adminPassword = pw1;
					adminSessionKey = null;
					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("비밀번호 변경됨", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					response.addHeader("Refresh", "2; url=/admin");
					return;
				} else if (methodType == 2 && url.equals("/admin_status_post")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if (decodedEntityToken[i].equals("submit=게임시작")) {
							if (gameData.status != GameStatus.INITAL) {
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity("게임이 진행 중입니다", ContentType.create("text/html", "UTF-8"));
								response.setEntity(nsEntity);
								response.addHeader("Refresh", "2; url=/admin");
								return;
							}

							Vector<CharacterType> v = new Vector<CharacterType>();
							for (Entry<CharacterType, Integer> e : characterQuotaMap.entrySet()) {
								System.out.println(e.getKey().getName() + "=" + e.getValue().intValue());
								for (int i1 = 0; i1 < e.getValue().intValue(); i1++) {
									v.addElement(e.getKey());
								}
							}
							int civilianCount = 0;
							while (v.size() < participantsMap.size()) {
								civilianCount++;
								v.addElement(CharacterType.CIVILIAN);
							}
							System.out.println(CharacterType.CIVILIAN.getName() + "=" + civilianCount);							
							for (Entry<String, Participant> e : participantsMap.entrySet()) {
								int rIndex = (int) (Math.random() * v.size());
								e.getValue().oriCharType = e.getValue().charType = v.elementAt(rIndex);
								System.out.println(e.getKey() + " -> " + e.getValue().charType.getName());
								v.removeElementAt(rIndex);
								e.getValue().reVote();

								// 군인용, 게임 시작 시에만 초기화
								e.getValue().tried = false;
							}

							if(gameData.policy_startFromDay) {
								gameData.status = GameStatus.DAY;
								gameData.logMsg += "[1일차 낮 시민투표 시작]<br>";
								System.out.println("gameData.status -> GameStatus.DAY");
							}
							else {
								gameData.status = GameStatus.NIGHT;
								gameData.logMsg += "[1일차 밤 마피아투표 시작]<br>";
								System.out.println("gameData.status -> GameStatus.NIGHT");							
							}
						} else if (decodedEntityToken[i].equals("submit=초기화")) {
							gameData.status = GameStatus.INITAL;
							gameData.days = 1;
							gameData.logMsg = "";
						}
					}

					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/admin_status");
					return;
				} else if (methodType == 1 && url.equals("/admin_participant")) {
					String body = new String("<h3>편집</h3><form action=/admin_participant_del method=post><table border=0><tr><th><th>이름<th>호칭<th>승점<th>세션키");
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						body += "<tr><td><input type=checkbox name=checkbox value=" + e.getKey() + "><td>" + e.getKey() + "<td>" + e.getValue().appellationName + "<td>" + e.getValue().wins + "<td>" + e.getValue().sessionKey;
					}
					body += "</table><input type=submit name=submit value=세션삭제> <input type=submit name=submit value=삭제> <input type=submit name=submit value=승점삭제><br></form>";
					body += "<h3>추가</h3><form action=/admin_participant_add method=post><table border=0><tr><th>이름<th>호칭<tr><td><input type=text name=accountName size=8><td><input type=text name=appellationName size=8></table><input type=submit name=submit value=추가><br></form>";
					body += "<a href=/admin>뒤로</a>";

					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("" + body + "", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					return;
				} else if (methodType == 2 && url.equals("/admin_participant_del")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					int mode = 0;
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if (decodedEntityToken[i].equals("submit=세션삭제"))
							mode = 1;
						else if (decodedEntityToken[i].equals("submit=삭제"))
							mode = 2;
						else if (decodedEntityToken[i].equals("submit=승점삭제"))
							mode = 3;
					}
					if (mode == 1)
						for (String s : decodedEntityToken) {
							if (s.startsWith("checkbox=")) {
								participantsMap.get(s.substring("checkbox=".length())).sessionKey = null;
							}
						}
					else if (mode == 2)
						for (String s : decodedEntityToken) {
							if (s.startsWith("checkbox=")) {
								participantsMap.remove(s.substring("checkbox=".length()));
							}
						}
					else if (mode == 3)
						for (String s : decodedEntityToken) {
							if (s.startsWith("checkbox=")) {
								participantsMap.get(s.substring("checkbox=".length())).wins = 0;
							}
						}
					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/admin_participant");
					return;
				} else if (methodType == 2 && url.equals("/admin_participant_add")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					String accountName = null;
					String appellationName = null;
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if (decodedEntityToken[i].startsWith("accountName=")) {
							accountName = decodedEntityToken[i].substring("accountName=".length());
						} else if (decodedEntityToken[i].startsWith("appellationName=")) {
							appellationName = decodedEntityToken[i].substring("appellationName=".length());
						}
					}
					if (accountName != null && appellationName != null) {
						participantsMap.put(accountName, new Participant(appellationName));
					}
					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/admin_participant");
					return;
				} else if (methodType == 1 && url.equals("/admin_range")) {
					String body = new String("현재 총원:" + participantsMap.size() + "<br>");
					body += "<form action=/admin_range_change method=post><table border=0><tr><th>직업<th>수";
					int maf = characterQuotaMap.get(CharacterType.MAFIA);
					int doc = characterQuotaMap.get(CharacterType.DOCTOR);
					int det = characterQuotaMap.get(CharacterType.DETECTIVE);
					int sol = characterQuotaMap.get(CharacterType.SOLDIER);
					body += "<tr><td>" + CharacterType.CIVILIAN + "<td>" + (participantsMap.size() - maf - doc - det - sol);
					body += "<tr><td>" + CharacterType.MAFIA + "<td><input type=text name=" + CharacterType.MAFIA + " value=" + maf + ">";
					body += "<tr><td>" + CharacterType.DOCTOR + "<td><input type=text name=" + CharacterType.DOCTOR + " value=" + doc + ">";
					body += "<tr><td>" + CharacterType.DETECTIVE + "<td><input type=text name=" + CharacterType.DETECTIVE + " value=" + det + ">";
					body += "<tr><td>" + CharacterType.SOLDIER + "<td><input type=text name=" + CharacterType.SOLDIER + " value=" + sol + ">";
					body += "</table><input type=submit name=submit value=변경><br></form>";
					body += "<a href=/admin>뒤로</a>";

					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("" + body + "", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					return;
				} else if (methodType == 2 && url.equals("/admin_range_change")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if (decodedEntityToken[i].startsWith(CharacterType.MAFIA + "=")) {
							characterQuotaMap.put(CharacterType.MAFIA, Integer.parseInt(decodedEntityToken[i].substring((CharacterType.MAFIA + "=").length())));
						} else if (decodedEntityToken[i].startsWith(CharacterType.DOCTOR + "=")) {
							characterQuotaMap.put(CharacterType.DOCTOR, Integer.parseInt(decodedEntityToken[i].substring((CharacterType.DOCTOR + "=").length())));
						} else if (decodedEntityToken[i].startsWith(CharacterType.DETECTIVE + "=")) {
							characterQuotaMap.put(CharacterType.DETECTIVE, Integer.parseInt(decodedEntityToken[i].substring((CharacterType.DETECTIVE + "=").length())));
						} else if (decodedEntityToken[i].startsWith(CharacterType.SOLDIER + "=")) {
							characterQuotaMap.put(CharacterType.SOLDIER, Integer.parseInt(decodedEntityToken[i].substring((CharacterType.SOLDIER + "=").length())));
						}
					}
					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/admin_range");
					return;
				} else if (methodType == 1 && url.equals("/admin_preference")) {
					String body = new String();
					body += "<form action=/admin_preference_change method=post><table border=0><tr><th><th>정책";
					body += "<tr><td><input type=checkbox name=checkbox value=1"+ (gameData.policy_startFromDay ? " checked" : "") + "><td>게임을 낮부터 시작할 것인가(반. 밤부터)";
					body += "<tr><td><input type=checkbox name=checkbox value=2"+ (gameData.policy_2MafiaKillsCivilan ? " checked" : "") + "><td>두 명의 마피아가 시민을 살해하게 할것인가(반. 과반수 살해)";
					body += "<tr><td><input type=checkbox name=checkbox value=3"+ (gameData.policy_letOtherKnowWhenDie ? " checked" : "") + "><td>시민이 처형되면 정체를 공개합니까";
					body += "</table><input type=submit name=submit value=변경><br></form>";
					body += "<a href=/admin>뒤로</a>";

					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("" + body + "", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					return;
				} else if (methodType == 2 && url.equals("/admin_preference_change")) {
					if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity == null) throw new HttpException();
					// For some reason, just putting the incoming entity into
					// the
					// response will not work. We have to buffer the message.
					String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
					gameData.policy_startFromDay = false;
					gameData.policy_2MafiaKillsCivilan = false;
					gameData.policy_letOtherKnowWhenDie = false;
					for (int i = 0; i < decodedEntityToken.length; i++) {
						decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
						if(decodedEntityToken[i].equals("checkbox=1")) gameData.policy_startFromDay = true;
						if(decodedEntityToken[i].equals("checkbox=2")) gameData.policy_2MafiaKillsCivilan = true;
						if(decodedEntityToken[i].equals("checkbox=3")) gameData.policy_letOtherKnowWhenDie = true;
					}
					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/admin_preference");
					return;
				}				
			}

			// 운영자 로그인
			if (methodType == 1 && url.equals("/admin")) {
				if (!isAdmin) {
					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("<form action=/admin_pw method=post>비번:<input type=password size=8 name=pw> <input type=submit><br></form>",
							ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					return;
				}

				response.setStatusCode(HttpStatus.SC_OK);
				NStringEntity nsEntity = new NStringEntity(""
						+ "<a href=/admin_status>게임 관리</a><br>"
						+ "<a href=/admin_participant>참가자 편집</a><br>"
						+ "<a href=/admin_range>캐릭터별 할당인원 설정</a><br>"
						+ "<a href=/admin_preference>정책 설정</a><br>"
						+ "", ContentType.create("text/html", "UTF-8"));
				response.setEntity(nsEntity);
				return;
			} else if (methodType == 2 && url.equals("/admin_pw")) {
				if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
				HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
				if (hEntity == null) throw new HttpException();
				// For some reason, just putting the incoming entity into the
				// response will not work. We have to buffer the message.
				String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
				if (decodedEntityToken.length != 1) throw new HttpException();
				for (int i = 0; i < decodedEntityToken.length; i++)
					decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
				if (!decodedEntityToken[0].startsWith("pw=")) throw new HttpException();
				String pw = decodedEntityToken[0].substring("pw=".length());
				if (!pw.equals(adminPassword)) {
					response.setStatusCode(HttpStatus.SC_OK);
					NStringEntity nsEntity = new NStringEntity("비밀번호가 틀림", ContentType.create("text/html", "UTF-8"));
					response.setEntity(nsEntity);
					response.addHeader("Refresh", "2; url=/admin");
					return;
				}
				UUID random = UUID.randomUUID();
				MafiaGameWeb.adminSessionKey = random.toString();
				response.setStatusCode(HttpStatus.SC_OK);
				response.addHeader("Set-Cookie", "adminSessionKey=" + random.toString());
				response.addHeader("Refresh", "0; url=/admin");
				return;
			}

			if (methodType == 2 && url.equals("/login")) {
				if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
				HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
				if (hEntity == null) throw new HttpException();
				// For some reason, just putting the incoming entity into the
				// response will not work. We have to buffer the message.
				String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
				for (int i = 0; i < decodedEntityToken.length; i++) {
					decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
					if (decodedEntityToken[i].startsWith("accountName=")) {
						String accountName = decodedEntityToken[i].substring("accountName=".length());
						if (accountName.length() == 0) {
							response.setStatusCode(HttpStatus.SC_OK);
							response.addHeader("Refresh", "0; url=/");
							return;
						}
						final Participant p1 = participantsMap.get(accountName);
						if (p1 == null) {
							response.setStatusCode(HttpStatus.SC_OK);
							NStringEntity nsEntity = new NStringEntity("등록되지 않은 사용자입니다", ContentType.create("text/html", "UTF-8"));
							response.setEntity(nsEntity);
							return;
						}

						if (p1.sessionKey != null) {
							response.setStatusCode(HttpStatus.SC_OK);
							NStringEntity nsEntity = new NStringEntity("이미 접속하고 있는 사용자 입니다<br>본인이 맞으시면 관리자에게 요청바랍니다", ContentType.create("text/html", "UTF-8"));
							response.setEntity(nsEntity);
							return;
						}

						UUID random = UUID.randomUUID();
						p1.sessionKey = random.toString();
						response.setStatusCode(HttpStatus.SC_OK);
						response.addHeader("Set-Cookie", "accountName=" + Base64.encode(accountName.getBytes()));
						response.addHeader("Set-Cookie", "sessionKey=" + random.toString());
						response.addHeader("Refresh", "0; url=/");
						return;
					}
				}

				response.setStatusCode(HttpStatus.SC_OK);
				response.addHeader("Refresh", "0; url=/");
				return;
			}

			final Participant p;
			if (cookieAccountName == null || (p = participantsMap.get(cookieAccountName)) == null || p.sessionKey == null || !p.sessionKey.equalsIgnoreCase(cookieSessionKey)) {
				response.setStatusCode(HttpStatus.SC_OK);
				NStringEntity nsEntity = new NStringEntity("<form action=/login method=post>이름:<input type=text name=accountName size=8> <input type=submit><br></form>",
						ContentType.create("text/html", "UTF-8"));
				response.setEntity(nsEntity);
				return;
			}
			// 이후 p가 본인을 의미

			if (methodType == 1 && url.equals("/")) {
				boolean autoRefresh = false;
				String body = new String("" + p.appellationName + ",<br>");
				switch(gameData.status) {
				case INITAL:
					body += "아직 게임이 시작되지 않았습니다<br>";
					autoRefresh = true;
					break;
				case NIGHT:
					body += "당신은 " + p.charType.getName() + "입니다<br>";
					if (p.done == false) {
						body += "<h3>오늘은 " + gameData.days + "일차 밤입니다</h3><br>"
								+ "밤에는 마피아가 시민들을 살해할 수 있습니다<br>"
								+ "마피아끼리는 다른 마피아를 화면상의 명단에서 작은 표식으로 확인할 수 있습니다<br>"
								+ "다수의 마피아가 특정 시민을 살해하기로 투표하면 오늘 밤 그 시민은 죽게 됩니다<br>"
								+ "마피아가 살해할 시민을 의사가 예측하여 투표하면 그 시민을 즉시 치료하여 죽음에서 구할 수 있습니다<br>"
								+ "탐정은 매일 한 명을 밤새 조사하여 정체를 알아낼 수 있습니다<br>"
								+ "군인은 마피아의 공격을 한 번 견뎌낼 수 있습니다<br>"
								+ "나머지 시민들도 투표를 해야 하지만 그 투표 결과는 의미가 없습니다<br>"
								+ "자! 공개 토론을 통해 투표를 진행 하십시오<br>"
								+ "<form action=/vote method=post><table border=0>";
						for (Entry<String, Participant> e : participantsMap.entrySet()) {
							if (e.getValue() != p || e.getValue().charType == CharacterType.DOCTOR) {
								if (e.getValue().charType == CharacterType.DEAD)
									body += "<tr><td><input type=radio name=radio value=" + e.getKey() + " disabled><td>" + e.getValue().appellationName;
								else
									body += "<tr><td><input type=radio name=radio value=" + e.getKey() + "><td>" + e.getValue().appellationName;
								if (p.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
									body += ".";
							}
						}
						body += "</table><br><input type=submit value=투표></form>";
					} else {
						body += "투표가 진행 중입니다<br>투표를 완료하지 않은 사람:";
						boolean first = true;
						for (Entry<String, Participant> e : participantsMap.entrySet()) {
							if (!e.getValue().done) {
								body += (first ? " " : ", ") + e.getValue().appellationName;
								first = false;
							}
						}
						autoRefresh = true;
					}
					break;
				case NIGHT_FINISHED:
				case DAY_FINISHED:
					if (p.done == false) {
						body += "당신은 " + p.charType.getName() + "입니다<br>";
						if (gameData.broadMsg != null) body += "방송 메시지: " + gameData.broadMsg;
						if (p.privateMsg != null) body += "개인 메시지: " + p.privateMsg + "";
						body += "<br><form action=/vote method=post>확인을 눌러주세요 <input type=submit value=확인></form>";
					} else {
						body += "당신은 " + p.charType.getName() + "입니다<br>";
						if (gameData.broadMsg != null) body += "방송 메시지: " + gameData.broadMsg;
						if (p.privateMsg != null) body += "개인 메시지: " + p.privateMsg + "";
						body += "<br>나머지 인원의 확인을 기다리는 중</form>";
						autoRefresh = true;
					}
					break;
				case DAY:
					body += "당신은 " + p.charType.getName() + "입니다<br>";
					if (p.done == false) {
						body += "<h3>오늘은 " + gameData.days + "일차 낮입니다</h3><br>"
								+ "낮에는 시민들이 투표를 통해 한 사람을 처형해야 합니다<br>"
								+ "시민들은 마피아를 구별할 수 없으므로 마피아도 똑같이 투표권을 가집니다<br>"
								+ "시민들은 먼저 토론을 통해 합의를 이루어야 하고, 투표 결과는 한 시민을 과반수 이상 지목해야 합니다<br>"
								+ "남은 인구의 과반수 이상이 마피아일 경우에는 투표 결과가 항상 선량한 시민을 향할 것이므로 더 이상의 진행은 무의미하며 게임이 종료됩니다<br>"
								+ "토론을 진행 하십시오<br>"
								+ "<form action=/vote method=post><table border=0>";
						for (Entry<String, Participant> e : participantsMap.entrySet()) {
							if (e.getValue() != p) {
								if (e.getValue().charType == CharacterType.DEAD)
									body += "<tr><td><input type=radio name=radio value=" + e.getKey() + " disabled><td>" + e.getValue().appellationName;
								else
									body += "<tr><td><input type=radio name=radio value=" + e.getKey() + "><td>" + e.getValue().appellationName;
								if (p.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
									body += ".";
							}
						}
						body += "</table><br><input type=submit value=투표></form>";
					} else {
						body += "투표가 진행 중입니다<br>투표를 완료하지 않은 사람:";
						boolean first = true;
						for (Entry<String, Participant> e : participantsMap.entrySet()) {
							if (!e.getValue().done) {
								body += (first ? " " : ", ") + e.getValue().appellationName;
								first = false;
							}
						}
						autoRefresh = true;
					}
					break;
				case GAME_FINISHED:
					if(p.oriCharType == CharacterType.MAFIA) {
						if(gameData.civilWins) {
							body += "<h3>마피아가 졌습니다</h3><br>";
						}
						else {
							body += "<h3>마피아가 이겼습니다</h3><br>";							
						}
					}
					else {
						if(gameData.civilWins) {
							body += "<h3>시민이 이겼습니다</h3><br>";
						}
						else {
							body += "<h3>시민이 졌습니다</h3><br>";							
						}						
					}
					body += "당신의 승점은 총 " + p.wins + "점 입니다<br>";
					if (gameData.broadMsg != null) body += "방송 메시지: " + gameData.broadMsg;
					if (p.privateMsg != null) body += "개인 메시지: " + p.privateMsg;
					body += "<br><h3>정체 공개</h3><br>";
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						body += e.getValue().appellationName + "(승점:" + e.getValue().wins + ") -> " + e.getValue().oriCharType.getName() + "<br>";
					}					
					body += "<br><h3>진행 기록</h3><br>" + gameData.logMsg;
					break;					
				}

				response.setStatusCode(HttpStatus.SC_OK);
				NStringEntity nsEntity = new NStringEntity("" + body + "", ContentType.create("text/html", "UTF-8"));
				response.setEntity(nsEntity);
//				if (autoRefresh) body += "<script type=\"text/javascript\">" + "setTimeout(function () { location.reload(); }, 3000);" + "</script>";
				if (autoRefresh) response.addHeader("Refresh", "2; url=/");
			} else if (methodType == 2 && url.equals("/vote")) {
				if (p.done) {
					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/");
					return;
				} else if (gameData.status == GameStatus.NIGHT_FINISHED || gameData.status == GameStatus.DAY_FINISHED) {
					System.out.println("Check " + p.appellationName);

					voteAndCheck(p);

					response.setStatusCode(HttpStatus.SC_OK);
					response.addHeader("Refresh", "0; url=/");
					return;
				}
				if (!(request instanceof HttpEntityEnclosingRequest)) throw new HttpException();
				HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
				if (hEntity == null) throw new HttpException();
				// For some reason, just putting the incoming entity into the
				// response will not work. We have to buffer the message.
				String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("[&]");
				for (int i = 0; i < decodedEntityToken.length; i++) {
					decodedEntityToken[i] = URLDecoder.decode(decodedEntityToken[i], "UTF-8");
					if (decodedEntityToken[i].startsWith("radio=")) {
						Participant p1 = participantsMap.get(decodedEntityToken[i].substring("radio=".length()));
						if (p1 == null) {
							response.setStatusCode(HttpStatus.SC_OK);
							NStringEntity nsEntity = new NStringEntity("시스템 오류", ContentType.create("text/html", "UTF-8"));
							response.setEntity(nsEntity);
							return;
						}
						
						System.out.println("Vote " + p.appellationName + " -> " + p1.appellationName);
						if (gameData.status == GameStatus.NIGHT) {
							switch (p.charType) {
							case DETECTIVE:
								p.privateMsg += p1.appellationName + "의 정체는 " + p1.charType.getName() + "입니다<br>";
								gameData.logMsg += "(탐정)" + p.appellationName + " -조사-> (" + p1.charType.getName() + ")" + p1.appellationName + "<br>";   
								break;
							case DOCTOR:
								p1.savedBy = p;
								gameData.logMsg += "(의사)" + p.appellationName + " -치료-> (" + p1.charType.getName() + ")" + p1.appellationName + "<br>";   
								break;
							case MAFIA:
								p1.voted++;
								gameData.logMsg += "(마피아)" + p.appellationName + " -암살-> (" + p1.charType.getName() + ")" + p1.appellationName + "<br>";   
								break;
							default:
								break;
							}
						} else if (gameData.status == GameStatus.DAY) {
							gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + " -> (" + p1.charType.getName() + ")" + p1.appellationName + "<br>";
							p1.voted++;
						} else {
							response.setStatusCode(HttpStatus.SC_OK);
							NStringEntity nsEntity = new NStringEntity("잘못된 요청입니다", ContentType.create("text/html", "UTF-8"));
							response.setEntity(nsEntity);
							return;
						}
						
						voteAndCheck(p);
						break;
					}
				}
				response.setStatusCode(HttpStatus.SC_OK);
				response.addHeader("Refresh", "0; url=/");
			}
			else {
				response.setStatusCode(HttpStatus.SC_NOT_FOUND);
				NStringEntity nsEntity = new NStringEntity("Page Not Found", ContentType.create("text/html", "UTF-8"));
				response.setEntity(nsEntity);
			}
		}
	}

	private static synchronized void voteAndCheck(Participant p) {
		p.done = true;
		
		boolean voteFinished = true;
		int maf = 0;
		int live = 0;
		for (Entry<String, Participant> e : participantsMap.entrySet()) {
			p = e.getValue();
			if (!p.done)
				voteFinished = false;
			if (p.charType == CharacterType.MAFIA)
				maf++;
			if (p.charType != CharacterType.DEAD)
				live++;
		}
		if (voteFinished) {
			switch(gameData.status) {
			case NIGHT:
				gameData.broadMsg = "";
				gameData.logMsg += "[" + gameData.days + "일차 밤 마피아투표 결과]<br>";				
				for (Entry<String, Participant> e : participantsMap.entrySet()) {
					p = e.getValue();
					if (p.charType != CharacterType.DEAD) {
						boolean killed = false;
						if(gameData.policy_2MafiaKillsCivilan) {
							if (p.voted >= 2 || (maf == 1 && p.voted == 1)) killed = true;
						}
						else {
							if (p.voted >= (maf / 2) + 1) killed = true;
						}
						if (killed) {
							if (p.charType == CharacterType.SOLDIER && p.tried == false) {
								p.privateMsg += "마피아 들의 공격으로 죽을 뻔 했습니다<br>";
								p.tried = true;
								gameData.broadMsg += "지난 밤 소란이 있었던 것 같습니다 (마피아의 공격이 성공하지 못한 것 같습니다)<br>";
								gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + "이 마피아의 공격을 한 번 방어 해 냄<br>";
							} else if (p.savedBy != null) {
								if (p == p.savedBy) {
									p.privateMsg += "마피아 들의 공격으로 죽음 직전까지 갔으나, 스스로를 치료 하였습니다<br>";
									gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + "이 마피아의 공격을 받았으나 스스로 치료함<br>";
								} else {
									p.privateMsg += "마피아 들의 공격으로 죽음 직전까지 갔으나, 어떤 의사가 살려주었습니다<br>";
									p.savedBy.privateMsg += p.appellationName + "이 마피아의 공격을 받았으나 구해냈습니다<br>";
									gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + "이 마피아의 공격을 받았으나 (의사)" + p.savedBy.appellationName + "이 구해줌<br>";
								}
								gameData.broadMsg += "지난 밤 소란이 있었던 것 같습니다 (마피아의 공격이 성공하지 못한 것 같습니다)<br>";
							} else {
								p.privateMsg = "당신은 죽었습니다<br>"; // 이전 privateMsg 삭제 (탐정의 조사 결과 등)
								gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + "이 마피아의 공격에의해 사망함<br>";
								gameData.broadMsg += p.appellationName + "이 마피아 들에 의해 암살 당했습니다<br>";
								p.charType = CharacterType.DEAD;
								live--;
							}
						}
						p.done = false;
					}
				}
				if (gameData.broadMsg.length() == 0) {
					gameData.broadMsg = "지난 밤에 죽은 사람은 없는 것 같습니다<br>";
				}
				if (live <= (maf * 2)) {
					gameData.broadMsg += "주민의 과반수 이상이 마피아이므로 이 마을은 마피아의 마을입니다<br>마피아가 승리 하였습니다<br>";
					gameData.status = GameStatus.GAME_FINISHED;
					System.out.println("gameData.status -> GameStatus.GAME_FINISHED");
					addWins(false);
					return;
				}
				gameData.status = GameStatus.NIGHT_FINISHED;
				System.out.println("gameData.status -> GameStatus.NIGHT_FINISHED");
				break;
			case NIGHT_FINISHED:
				for (Entry<String, Participant> e : participantsMap.entrySet()) {
					e.getValue().reVote();
				}
				gameData.status = GameStatus.DAY;
				gameData.days++;
				gameData.logMsg += "[" + gameData.days + "일차 낮 시민투표 시작]<br>";
				System.out.println("gameData.status -> GameStatus.DAY");
				break;
			case DAY:
				gameData.broadMsg = "";
				gameData.logMsg += "[" + gameData.days + "일차 낮 시민투표 결과]<br>";
				boolean killed = false;
				for (Entry<String, Participant> e : participantsMap.entrySet()) {
					p = e.getValue();
					if (p.charType != CharacterType.DEAD) {
						if (p.voted >= (live / 2) + 1) {
							p.privateMsg += "당신은 사형 당했습니다<br>유령이 되면 더 이상 발언할 수 없습니다<br>";
							gameData.broadMsg += p.appellationName + "이 시민 투표를 통해 처형 되었습니다<br>";
							gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + " " + p.voted + "표 -> 사형<br>";
							if(gameData.policy_letOtherKnowWhenDie)
								gameData.broadMsg += p.appellationName + "의 정체는 " + p.charType.getName() + "(이)였습니다<br>";
							if (p.charType == CharacterType.MAFIA)
								maf--;
							p.charType = CharacterType.DEAD;
							live--;
							killed = true;
						}
						else {
							gameData.logMsg += "(" + p.charType.getName() + ")" + p.appellationName + " " + p.voted + "표<br>";							
						}
						p.done = false;
					}
				}
				if (killed == false) {
					gameData.broadMsg = "처형할 시민을 합의하지 못했습니다<br>다시 투표 해 주십시오<br>";
					gameData.logMsg += "합의 실패<br>";
					gameData.days--;
					gameData.status = GameStatus.NIGHT_FINISHED;
					System.out.println("gameData.status -> GameStatus.NIGHT_FINISHED");
					break;
				}
				if (live <= (maf * 2)) {
					gameData.broadMsg += "주민의 과반수 이상이 마피아이므로 이 마을은 마피아의 마을입니다<br>마피아가 승리 하였습니다<br>";
					gameData.status = GameStatus.GAME_FINISHED;
					System.out.println("gameData.status -> GameStatus.GAME_FINISHED");
					addWins(false);					
					return;
				}
				if (maf == 0) {
					gameData.broadMsg += "마을의 모든 마피아를 제거하였습니다<br>주민 들이 승리 하였습니다<br>";
					gameData.status = GameStatus.GAME_FINISHED;
					System.out.println("gameData.status -> GameStatus.GAME_FINISHED");
					addWins(true);					
					return;
				}
				gameData.status = GameStatus.DAY_FINISHED;
				System.out.println("gameData.status -> GameStatus.DAY_FINISHED");
				break;
			case DAY_FINISHED:
				for (Entry<String, Participant> e : participantsMap.entrySet()) {
					e.getValue().reVote();
				}
				gameData.status = GameStatus.NIGHT;
				gameData.logMsg += "[" + gameData.days + "일차 밤 마피아투표 시작]<br>";
				System.out.println("gameData.status -> GameStatus.NIGHT");
				break;
			default:
				break;
			}
		}
	}
	
	public static void addWins(boolean civilWins) {
		gameData.civilWins = civilWins; 
		for (Entry<String, Participant> e : participantsMap.entrySet()) {
			if(e.getValue().oriCharType == CharacterType.MAFIA) {
				if(!civilWins) {
					e.getValue().wins++;
				}
			}
			else {
				if(civilWins) {
					e.getValue().wins++;
				}				
			}
		}		
	}

	public static void main(String args[]) throws Exception {

		
		participantsMap.put("고해곤", new Participant("해곤님"));
		participantsMap.put("김상희", new Participant("상희님"));
		participantsMap.put("김태준", new Participant("태준님"));
		participantsMap.put("김현일", new Participant("현일님"));
		participantsMap.put("손평기", new Participant("평기님"));
		participantsMap.put("안창환", new Participant("창환님"));
		participantsMap.put("임채선", new Participant("채선님"));
		participantsMap.put("전충현", new Participant("충현님"));
		participantsMap.put("정태경", new Participant("태경님"));
		participantsMap.put("천성심", new Participant("성심님"));
		participantsMap.put("홍명환", new Participant("명환님"));
		
		characterQuotaMap.put(CharacterType.MAFIA, 4);
		characterQuotaMap.put(CharacterType.DOCTOR, 2);
		characterQuotaMap.put(CharacterType.DETECTIVE, 1);
		characterQuotaMap.put(CharacterType.SOLDIER, 1);
		
		// Create HTTP protocol processing chain
		HttpProcessor httpproc = HttpProcessorBuilder.create()
				.add(new ResponseDate())
				.add(new ResponseServer("MafiaGameWeb/1.0"))
				.add(new ResponseContent()).add(new ResponseConnControl())
				.build();

		// Create request handler registry
		UriHttpAsyncRequestHandlerMapper reqistry = new UriHttpAsyncRequestHandlerMapper();

		// Register the default handler for all URIs
		reqistry.register("/*", new HttpHandlerForWebVer());

		// Create server-side HTTP protocol handler
		HttpAsyncService protocolHandler = new HttpAsyncService(httpproc, reqistry) {

			@Override
			public void connected(final NHttpServerConnection conn) {
				super.connected(conn);
			}

			@Override
			public void closed(final NHttpServerConnection conn) {
				super.closed(conn);
			}
		};
		
		// Create HTTP connection factory
		NHttpConnectionFactory<DefaultNHttpServerConnection> connFactory = new DefaultNHttpServerConnectionFactory(ConnectionConfig.DEFAULT);

		// Create server-side I/O event dispatch
		IOEventDispatch ioEventDispatch = new DefaultHttpServerIODispatch(protocolHandler, connFactory);

		// Set I/O reactor defaults
		IOReactorConfig config = IOReactorConfig.custom().setIoThreadCount(1).setSoTimeout(3000).setConnectTimeout(3000).build();

		// Create server-side I/O reactor
		ListeningIOReactor ioReactor = new DefaultListeningIOReactor(config);

		try {
			// Listen of the given PORT
			ioReactor.listen(new InetSocketAddress(PORT));
			// Ready to go!
			ioReactor.execute(ioEventDispatch);
		} catch (InterruptedIOException ex) {

		} catch (IOException e) {

		}
	}
}

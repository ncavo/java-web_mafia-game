import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
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

public class MafiaGameAndroid {

	static private final int PORT = 8080;

	static private final String PASSWORD_FILE_NAME = "user_data_01.dat";
	static private final String NICKNAME_FILE_NAME = "user_data_02.dat";
	static private final String GAMEDATA_FILE_NAME = "game_data_temp%s.dat";
	
	static private Map<String, String> mUserPasswordMap = null;
	static private final Lock mUserPasswordLock = new ReentrantLock();

	static private Map<String, String> mUserNicknameMap = null;
	static private final Lock mUserNickNameLock = new ReentrantLock(); 
	
	static private final Map<String, String> mUserSessionKeyMap = new HashMap<String, String>();
	
	static enum CharacterType implements Serializable {
		
		CIVILIAN("�ù�"), MAFIA("���Ǿ�"), DOCTOR("�ǻ�"), DETECTIVE("Ž��"), SOLDIER("����"), DEAD("����");

		private final String name;

		CharacterType(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}
	}
	
	static class Participant implements Serializable {

		private static final long serialVersionUID = 8272367974621319438L;

		public Participant(String email, String nickname) {
			this.email = email;
			this.nickname = nickname;
			try {
				this.decoded_nickname = new String(Base64.decode(nickname.getBytes("UTF-8")));
			} catch (Base64DecodingException e) {
				this.decoded_nickname = "";
			} catch (UnsupportedEncodingException e) {
				this.decoded_nickname = "";
			}
		}
		
		public String email;
		public String nickname;
		public String decoded_nickname;
		
		public int wins = 0;
		
		public CharacterType oriCharType = CharacterType.CIVILIAN;
		public CharacterType charType = CharacterType.CIVILIAN;

		// ��ǥ ����
		public boolean done = false;

		public int voted = 0;
		public Participant savedBy = null;
		public String privateMsg = "";

		// ���ο�, ���� ���� �� �ѹ��� �ʱ�ȭ
		public boolean tried = false;

		public void init() {
			if (charType == CharacterType.DEAD)
				done = true;
			else
				done = false;

			voted = 0;
			savedBy = null;
			privateMsg = "";
		}
	}

	static enum GameStatus {
		INITAL, NIGHT, NIGHT_FINISHED, DAY, DAY_FINISHED, GAME_FINISHED
	}
	
	static private class Game implements Serializable {

		private static final long serialVersionUID = 5682291669636936654L;

		public Game(String roomName) {
			try {
				this.decoded_name = new String(Base64.decode(roomName.getBytes("UTF-8")));
			} catch (Base64DecodingException e) {
				this.decoded_name = "";
			} catch (UnsupportedEncodingException e) {
				this.decoded_name = "";
			}
		}		
		
		public String decoded_name;
		public String owner;
		public String password;
		public int maxPlayers;

		public final Map<String, Participant> participantsMap = new HashMap<String, Participant>();
		
		public GameStatus status = GameStatus.INITAL;
		public int days = 1;
		public String logMsg = "";

		public String broadMsg = null;
		public boolean civilWins = false;

		public boolean policy_startFromDay = true;
		public boolean policy_2MafiaKillsCivilan = false;
		public boolean policy_letOtherKnowWhenDie = true;

		public final Map<CharacterType, Integer> characterQuotaMap = new HashMap<CharacterType, Integer>();
		
		public void removePlayer(String email) {
			
			if(owner.equalsIgnoreCase(email)) return;
			
			if(status == GameStatus.INITAL) {
				participantsMap.remove(email);
				return;
			}
			
//			checkVoteDone();
		}
		private void checkVoteDone() {
			boolean not = false;
			int maf = 0;
			int live = 0;
			for (Entry<String, Participant> e : participantsMap.entrySet()) {
				Participant p = e.getValue();
				if (!p.done)
					not = true;
				if (p.charType == CharacterType.MAFIA)
					maf++;
				if (p.charType != CharacterType.DEAD)
					live++;
			}
			if (!not) {
				switch(status) {
				case NIGHT:
					broadMsg = "";
					logMsg += "\r\n[" + days + "���� �� ���Ǿ���ǥ ���]\r\n";				
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						Participant p = e.getValue();
						if (p.charType != CharacterType.DEAD) {
							boolean kill = false;
							if(policy_2MafiaKillsCivilan) {
								if (p.voted >= 2 || (maf == 1 && p.voted == 1)) kill = true;
							}
							else {
								if (p.voted >= (maf / 2) + 1) kill = true;
							}
							if (kill) {
								if (p.charType == CharacterType.SOLDIER && p.tried == false) {
									p.privateMsg += "���Ǿ� ���� �������� ���� �� �߽��ϴ�\r\n";
									p.tried = true;
									broadMsg += "���� �� �Ҷ��� �־��� �� �����ϴ� (���Ǿ��� ������ �������� ���� �� �����ϴ�)\r\n";
									logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + "�� ���Ǿ��� ������ �ѹ� ��� �� ��\r\n";
								} else if (p.savedBy != null) {
									if (p.email.equals(p.savedBy.email)) {
										p.privateMsg += "���Ǿ� ���� �������� ���� �������� ������, �����θ� ġ�� �Ͽ����ϴ�\r\n";
										logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + "�� ���Ǿ��� ������ �޾����� ������ ġ����\r\n";
									} else {
										p.privateMsg += "���Ǿ� ���� �������� ���� �������� ������, �͸��� �ǻ簡 ����ּ̽��ϴ�\r\n";
										p.savedBy.privateMsg += p.decoded_nickname + "�� ���Ǿ��� ������ �޾����� ���س½��ϴ�\r\n";
										logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + "�� ���Ǿ��� ������ �޾����� (�ǻ�)" + p.savedBy.decoded_nickname + "�� ������\r\n";
									}
									broadMsg += "���� �� �Ҷ��� �־��� �� �����ϴ� (���Ǿ��� ������ �������� ���� �� �����ϴ�)\r\n";
								} else {
									p.privateMsg += "����� �׾����ϴ�\r\n";
									logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + "�� ���Ǿ��� ���ݿ����� �����\r\n";
									if (p.charType == CharacterType.MAFIA)
										maf--;
									p.charType = CharacterType.DEAD;
									live--;
									broadMsg += p.decoded_nickname + "�� ���Ǿ� �鿡 ���� �ϻ� ���߽��ϴ�\r\n";
								}
							}
							p.done = false;
						}
					}
					if (broadMsg.length() == 0) {
						broadMsg = "���� �㿡 ���� ����� ���� �� �����ϴ�\r\n";
					}
					if (live <= (maf * 2)) {
						broadMsg += "�ֹ��� ���ݼ� �̻��� ���Ǿ��̹Ƿ� �� ������ ���Ǿ��� �����Դϴ�\r\n"
								+ "���Ǿư� �¸� �Ͽ����ϴ�\r\n";
						status = GameStatus.GAME_FINISHED;
						System.out.println("status -> GameStatus.GAME_FINISHED");
						addWins(false);
						return;
					}
					status = GameStatus.NIGHT_FINISHED;
					System.out.println("status -> GameStatus.NIGHT_FINISHED");
					break;
				case NIGHT_FINISHED:
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						e.getValue().init();
					}
					status = GameStatus.DAY;
					days++;
					logMsg += "\r\n[" + days + "���� �� �ù���ǥ ����]\r\n";
					System.out.println("status -> GameStatus.DAY");
					break;
				case DAY:
					broadMsg = "";
					logMsg += "\r\n[" + days + "���� �� �ù���ǥ ���]\r\n";
					boolean killed = false;
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						Participant p = e.getValue();
						if (p.charType != CharacterType.DEAD) {
							if (p.voted >= (live / 2) + 1) {
								p.privateMsg += "����� ���� ���߽��ϴ�\r\n";
								broadMsg += p.decoded_nickname + "�� �ù� ��ǥ�� ���� ó�� �Ǿ����ϴ�\r\n";
								logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + " " + p.voted + "ǥ -> ����\r\n";
								if(policy_letOtherKnowWhenDie)
									broadMsg += p.decoded_nickname + "�� ��ü�� " + p.charType.getName() + "(��)�����ϴ�\r\n";
								if (p.charType == CharacterType.MAFIA)
									maf--;
								p.charType = CharacterType.DEAD;
								live--;
								killed = true;
							}
							else {
								logMsg += "(" + p.charType.getName() + ")" + p.decoded_nickname + " " + p.voted + "ǥ\r\n";							
							}
							p.done = false;
						}
					}
					if (killed == false) {
						broadMsg = "ó���� �ù��� �������� ���߽��ϴ�\r\n"
								+ "�ٽ� ��ǥ �� �ֽʽÿ�\r\n";
						logMsg += "���� ����\r\n";
						days--;
						status = GameStatus.NIGHT_FINISHED;
						System.out.println("status -> GameStatus.NIGHT_FINISHED");
						break;
					}
					if (live <= (maf * 2)) {
						broadMsg += "�ֹ��� ���ݼ� �̻��� ���Ǿ��̹Ƿ� �� ������ ���Ǿ��� �����Դϴ�\r\n"
								+ "���Ǿư� �¸� �Ͽ����ϴ�\r\n";
						status = GameStatus.GAME_FINISHED;
						System.out.println("status -> GameStatus.GAME_FINISHED");
						addWins(false);					
						return;
					}
					if (maf == 0) {
						broadMsg += "������ ��� ���ǾƸ� �˰��Ͽ����ϴ�\r\n"
								+ "�ֹ� ���� �¸� �Ͽ����ϴ�\r\n";
						status = GameStatus.GAME_FINISHED;
						System.out.println("status -> GameStatus.GAME_FINISHED");
						addWins(true);					
						return;
					}
					status = GameStatus.DAY_FINISHED;
					System.out.println("status -> GameStatus.DAY_FINISHED");
					break;
				case DAY_FINISHED:
					for (Entry<String, Participant> e : participantsMap.entrySet()) {
						e.getValue().init();
					}
					status = GameStatus.NIGHT;
					logMsg += "\r\n[" + days + "���� �� ���Ǿ���ǥ ����]\r\n";
					System.out.println("status -> GameStatus.NIGHT");
					break;
				default:
					break;
				}
			}
		}
		
		public void addWins(boolean civilWins) {
			this.civilWins = civilWins; 
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
	}
	
	static private Map<String, Game> mGameMap = new HashMap<String, Game>();
	static private final Lock mGameLock = new ReentrantLock(); 
		
	static private class HttpHandler implements HttpAsyncRequestHandler<HttpRequest> {

		public HttpHandler() {
			super();
		}

		@Override
		public HttpAsyncRequestConsumer<HttpRequest> processRequest(final HttpRequest request, final HttpContext context) {
			// Buffer request content in memory for simplicity
			return new BasicAsyncRequestConsumer();
		}

		@Override
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

			// Get Cookie
/*			Header[] cookies = request.getHeaders("Cookie");
			for (Header h : cookies) {
				String token[] = h.getValue().split("[;]");
				for (String c : token) {
					int indexOfSep = c.indexOf('=');
					String left = c.substring(0, indexOfSep).trim();
					String right = c.substring(indexOfSep + 1, c.length()).trim();

				
				}
			} */
			
			if(methodType == 1) {
			
			}
			else if(methodType == 2) {
				if (request instanceof HttpEntityEnclosingRequest) {
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity != null) {
						String decodedEntityToken[] = new String(new String(EntityUtils.toByteArray(hEntity))).split("\r\n");
						if(decodedEntityToken.length > 0) {

							if(url.equalsIgnoreCase("/login")) {
	
								String email = null;
								String password = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("password")) {
					                	password = value;
					                }									
								}
								if(email != null && password != null) {
									System.out.println("POST /login");
									System.out.println(String.format("email: %s", email));
									
									String d1 = mUserPasswordMap.get(email);
									if(d1 == null) {
										System.out.println(String.format("unknown account"));
										System.out.println();

										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=no_email"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;											
									}
									if(!d1.equals(password)) {
										System.out.println(String.format("wrong password"));
										System.out.println();

										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_password"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;
									}
									
									String sessionKey = UUID.randomUUID().toString();
									mUserSessionKeyMap.put(email, sessionKey);

									System.out.println(String.format("ok session key=%s", sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=ok&nick_name=%s&session_key=%s", mUserNicknameMap.get(email), sessionKey), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;
								}
							}
							
							if(url.equalsIgnoreCase("/register")) {

								String email = null;
								String password = null;
								String nickname = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("password")) {
					                	password = value;
					                }									
					                else if(key.equalsIgnoreCase("nickname")) {
					                	nickname = value;
					                }									
								}
								if(email != null && password != null && nickname != null) {
									if(email.length() > 2 && password.length() > 4 && nickname.length() > 1) {
										System.out.println("POST /register");
										System.out.println(String.format("email: %s", email));
										System.out.println(String.format("password: %s", password));
										System.out.println(String.format("nickname: %s", nickname));
										
										String d1 = mUserPasswordMap.get(email);
										if(d1 != null) {
											
											System.out.println(String.format("email duplicated"));
											System.out.println();
											
											response.setStatusCode(HttpStatus.SC_OK);
											NStringEntity nsEntity = new NStringEntity(String.format("result=email_duplicated"), ContentType.create("text/plain", "UTF-8"));
											response.setEntity(nsEntity);
											return;											
										}
										for(Entry<String, String> e : mUserNicknameMap.entrySet()) {
											if(e.getValue().equals(nickname)) {

												System.out.println(String.format("nickname duplicated"));
												System.out.println();
												
												response.setStatusCode(HttpStatus.SC_OK);
												NStringEntity nsEntity = new NStringEntity(String.format("result=nickname_duplicated"), ContentType.create("text/plain", "UTF-8"));
												response.setEntity(nsEntity);
												return;																							
											}
										}

										mUserPasswordLock.lock();
										mUserNickNameLock.lock();
										
										mUserPasswordMap.put(email, password);
										mUserNicknameMap.put(email, nickname);
										
										try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(PASSWORD_FILE_NAME))) {
											oos.writeObject(mUserPasswordMap);
										}
										
										try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(NICKNAME_FILE_NAME))) {
											oos.writeObject(mUserNicknameMap);
										}
										
										mUserPasswordLock.unlock();
										mUserNickNameLock.unlock();

										String sessionKey = UUID.randomUUID().toString();
										mUserSessionKeyMap.put(email, sessionKey);

										System.out.println(String.format("ok session key=%s", sessionKey));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=ok&session_key=%s",sessionKey), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;
									}
								}
							}
							
							if(url.equalsIgnoreCase("/NewGame")) {

								String email = null;
								String sessionKey = null;
								String roomName = null;
								String maxPlayer = null;
								String password = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
					                else if(key.equalsIgnoreCase("room_name")) {
					                	roomName = value;
					                }									
					                else if(key.equalsIgnoreCase("max_player")) {
					                	maxPlayer = value;
					                }									
					                else if(key.equalsIgnoreCase("password")) {
					                	password = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();

									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}
								if(roomName != null && maxPlayer != null) {
									System.out.println("POST /NewGame");
									System.out.println(String.format("roomName: %s", roomName));
									System.out.println(String.format("maxPlayer: %s", maxPlayer));
									System.out.println(String.format("password: %s", password));

									if(mGameMap.get(roomName) != null) {
										System.out.println(String.format("duplicated room name(%s)", roomName));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=dup_room_name"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;																			
									}
									
									Game game = new Game(roomName);
									game.owner = email;
									game.maxPlayers = Integer.parseInt(maxPlayer);
									if(game.maxPlayers < 3) {
										System.out.println(String.format("wrong max player number(%d)", game.maxPlayers));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_max_player"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;																			
									}
									if(password != null && password.length() > 0) {
										game.password = password;
									}
									else {
										game.password = null;
									}
									game.participantsMap.put(email, new Participant(email, mUserNicknameMap.get(email)));
									
									mGameLock.lock();
									
									if(mGameMap.get(roomName) != null) {
										mGameLock.unlock();
										
										System.out.println(String.format("duplicated room name(%s)", roomName));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=dup_room_name"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;																			
									}
									
									mGameMap.put(roomName, game);
									mGameLock.unlock();

									System.out.println(String.format("ok"));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=ok"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;
								}
							}
							
							if(url.equalsIgnoreCase("/RoomList")) {

								String email = null;
								String sessionKey = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}
								System.out.println("POST /RoomList");
								String roomList = "result=ok" + System.lineSeparator();
								
								mGameLock.lock();
								int index = 0;
								for(Entry<String, Game> e : mGameMap.entrySet()) {

									roomList += String.format("%04d", index);
									roomList += mUserNicknameMap.get(e.getValue().owner);
									roomList += System.lineSeparator();
									
									roomList += String.format("%04d", index);
									roomList += e.getValue().status.toString();
									roomList += System.lineSeparator();
									
									roomList += String.format("%04d", index);
									roomList += e.getKey();
									roomList += System.lineSeparator();
									
									roomList += String.format("%04d", index);
									roomList += e.getValue().password == null ? "0" : "1";
									roomList += System.lineSeparator();
									
									roomList += String.format("%04d", index);
									roomList += String.format("%d/%d", e.getValue().participantsMap.size(), e.getValue().maxPlayers);
									roomList += System.lineSeparator();
									
									roomList += String.format("%04d", index);
									roomList += e.getValue().participantsMap.get(email) == null ? "0" : "1";
									roomList += System.lineSeparator();

									index++;
								}
								mGameLock.unlock();

								System.out.println(roomList);
								System.out.println();
								
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity(roomList, ContentType.create("text/plain", "UTF-8"));
								response.setEntity(nsEntity);
								return;									
							}
							
							if(url.equalsIgnoreCase("/GameView")) {
								System.out.println("POST /GameView");
								
								String email = null;
								String sessionKey = null;
								String roomName = null;
								
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
					                else if(key.equalsIgnoreCase("room_name")) {
					                	roomName = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}
								
								mGameLock.lock();
								Game game = mGameMap.get(roomName);
								if(game == null) {
									mGameLock.unlock();
									System.out.println(String.format("no room email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=no_room"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																		
								}

								Participant part = game.participantsMap.get(email);
								if(part == null) {
									if(game.status == GameStatus.INITAL) {
										if(game.participantsMap.size() < game.maxPlayers) {
											game.participantsMap.put(email, new Participant(email, mUserNicknameMap.get(email)));
										}
										else {
											mGameLock.unlock();
											System.out.println(String.format("full, email=%s, room=%s", email, roomName));
											System.out.println();
											
											response.setStatusCode(HttpStatus.SC_OK);
											NStringEntity nsEntity = new NStringEntity(String.format("result=room_full"), ContentType.create("text/plain", "UTF-8"));
											response.setEntity(nsEntity);
											return;																																							
										}
									}
									else {
										mGameLock.unlock();
										System.out.println(String.format("can't enter, email=%s, room=%s", email, roomName));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;																												
									}
								}
								
								String resultMsg = "";
								if(game.status == GameStatus.INITAL) {
									if(game.owner.equalsIgnoreCase(email)) {
										resultMsg = "status=init/owner" + System.lineSeparator();
									}
									else {
										resultMsg = "status=init" + System.lineSeparator();									
									}
									
									int index = 0;
									resultMsg += String.format("%04d", index);
									resultMsg += "���� ������ ���۵��� �ʾҽ��ϴ�.";
									resultMsg += System.lineSeparator();
	
									index++;
									
									for(Entry<String, Participant> e : game.participantsMap.entrySet()) {
	
										resultMsg += String.format("%04d", index);
										resultMsg += e.getKey();
										resultMsg += System.lineSeparator();
										
										resultMsg += String.format("%04d", index);
										resultMsg += mUserNicknameMap.get(e.getKey());
										resultMsg += System.lineSeparator();
										
										index++;
									}
								}
								else if(game.status == GameStatus.NIGHT) {
									if(part.done == false)
										resultMsg = "status=vote" + System.lineSeparator();
									else
										resultMsg = "status=wait" + System.lineSeparator();
									
									resultMsg += "����� " + part.charType.getName() + "�Դϴ�\r\n";
									if (part.done == false) {
										resultMsg += "������ " + game.days + "���� ���Դϴ�\r\n"
												+ "�㿡�� ���Ǿư� �ùε��� ������ �� �ֽ��ϴ�\r\n"
												+ "���Ǿƴ� �ٸ� ���Ǿ� ���� ��ܿ��� ���� ǥ������ �� �� �ֽ��ϴ�\r\n"
												+ "�θ� �̻��� ���Ǿư� �� �ù��� ��ǥ�� �ϸ� ���� �� �� �ù��� �װ� �˴ϴ�\r\n"
												+ "�ǻ�� ���Ǿư� ������ �ù��� �����Ͽ� �����ϸ� �� �ù��� ��� ġ���ϰ� �������� ���� �� �ֽ��ϴ�\r\n"
												+ "Ž���� �� ���� ��� �����Ͽ� ��ü�� �˾Ƴ� �� �ֽ��ϴ�\r\n"
												+ "������ ���Ǿ��� ������ �ѹ� ���Ƴ� �� �ֽ��ϴ�\r\n"
												+ "������ �ùε鵵 ��ǥ�� �ؾ� ������ �� ��ǥ ����� �ǹ̰� �����ϴ�\r\n"
												+ "��ǥ�� ���� �Ͻʽÿ�\r\n";

										int index = 1;
										for(Entry<String, Participant> e : game.participantsMap.entrySet()) {
											if (e.getValue() != part || e.getValue().charType == CharacterType.DOCTOR) {
												if (e.getValue().charType == CharacterType.DEAD) {
													resultMsg += String.format("%04d", index);
													resultMsg += e.getKey();
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += mUserNicknameMap.get(e.getKey());
													if (part.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
														resultMsg += "(���Ǿ�)";														
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += "0";
													resultMsg += System.lineSeparator();
												}
												else
												{
													resultMsg += String.format("%04d", index);
													resultMsg += e.getKey();
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += mUserNicknameMap.get(e.getKey());
													if (part.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
														resultMsg += "(���Ǿ�)";														
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += "1";
													resultMsg += System.lineSeparator();												
												}
											}
											index++;
										}
									} else {
										resultMsg += "��ǥ�� ���� ���Դϴ�\r\n" + "��ǥ�� �Ϸ����� ���� ���:\r\n";
										int index = 1; 
										for (Entry<String, Participant> e : game.participantsMap.entrySet()) {
											if (!e.getValue().done) {
												resultMsg += String.format("%04d", index);
												resultMsg += e.getKey();
												resultMsg += System.lineSeparator();
												
												resultMsg += String.format("%04d", index);
												resultMsg += mUserNicknameMap.get(e.getKey());
												resultMsg += System.lineSeparator();
												
												index++;
											}
										}
									}
								}
								else if(game.status == GameStatus.NIGHT_FINISHED || game.status == GameStatus.DAY_FINISHED) {
									if(part.done == false)
										resultMsg = "status=confirm" + System.lineSeparator();
									else
										resultMsg = "status=wait" + System.lineSeparator();

									if (part.done == false) {
										resultMsg += "����� " + part.charType.getName() + "�Դϴ�\r\n";
										if (game.broadMsg != null) resultMsg += "\r\n��� �޽���: " + game.broadMsg;
										if (part.privateMsg != null) resultMsg += "\r\n���� �޽���: " + part.privateMsg + "";
										resultMsg += "\r\nȮ���� �����ּ���";
									} else {
										resultMsg += "����� " + part.charType.getName() + "�Դϴ�\r\n";
										if (game.broadMsg != null) resultMsg += "\r\n��� �޽���: " + game.broadMsg;
										if (part.privateMsg != null) resultMsg += "\r\n���� �޽���: " + part.privateMsg + "";
										resultMsg += "\r\n������ �ο��� Ȯ���� ��ٸ��� ��";
									}
								}
								else if(game.status == GameStatus.DAY) {
									if(part.done == false)
										resultMsg = "status=vote" + System.lineSeparator();
									else
										resultMsg = "status=wait" + System.lineSeparator();

									resultMsg += "����� " + part.charType.getName() + "�Դϴ�\r\n";
									if (part.done == false) {
										resultMsg += "������ " + game.days + "���� ���Դϴ�\r\n"
												+ "������ �ùε��� ��ǥ�� ���� �� ����� ó���ؾ� �մϴ�\r\n"
												+ "�ùε��� ���ǾƸ� ������ �� �����Ƿ� ���ǾƵ� �Ȱ��� ��ǥ���� �����ϴ�\r\n"
												+ "�ùε��� ���� ����� ���� ���Ǹ� �̷��� �ϰ�, ��ǥ ����� �� �ù��� ���ݼ� �̻� �����ؾ� �մϴ�\r\n"
												+ "���� �α��� ���ݼ� �̻��� ���Ǿ��� ��쿡�� ��ǥ ����� �׻� ������ �ù��� ���� ���̹Ƿ� �� �̻��� ������ ���ǹ��ϸ� ������ ����˴ϴ�\r\n"
												+ "����� ���� �Ͻʽÿ�\r\n";

										int index = 1;
										for (Entry<String, Participant> e : game.participantsMap.entrySet()) {
											if (e.getValue() != part) {
												if (e.getValue().charType == CharacterType.DEAD) {
													resultMsg += String.format("%04d", index);
													resultMsg += e.getKey();
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += mUserNicknameMap.get(e.getKey());
													if (part.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
														resultMsg += "(���Ǿ�)";														
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += "0";
													resultMsg += System.lineSeparator();
												}
												else
												{
													resultMsg += String.format("%04d", index);
													resultMsg += e.getKey();
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += mUserNicknameMap.get(e.getKey());
													if (part.charType == CharacterType.MAFIA && e.getValue().charType == CharacterType.MAFIA)
														resultMsg += "(���Ǿ�)";														
													resultMsg += System.lineSeparator();
													
													resultMsg += String.format("%04d", index);
													resultMsg += "1";
													resultMsg += System.lineSeparator();												
												}
											}
											index++;
										}
									} else {
										resultMsg += "��ǥ�� ���� ���Դϴ�\r\n" + "��ǥ�� �Ϸ����� ���� ���:\r\n";
										int index = 1; 
										for (Entry<String, Participant> e : game.participantsMap.entrySet()) {
											if (!e.getValue().done) {
												resultMsg += String.format("%04d", index);
												resultMsg += e.getKey();
												resultMsg += System.lineSeparator();
												
												resultMsg += String.format("%04d", index);
												resultMsg += mUserNicknameMap.get(e.getKey());
												resultMsg += System.lineSeparator();

												index++;
											}
										}
									}
								}
								else if(game.status == GameStatus.GAME_FINISHED) {
									if(game.owner.equalsIgnoreCase(email)) {
										resultMsg = "status=init/owner" + System.lineSeparator();
									}
									else {
										resultMsg = "status=init" + System.lineSeparator();									
									}
									
									if(part.oriCharType == CharacterType.MAFIA) {
										if(game.civilWins) {
											resultMsg += "���Ǿư� �����ϴ�\r\n";
										}
										else {
											resultMsg += "���Ǿư� �̰���ϴ�\r\n";							
										}
									}
									else {
										if(game.civilWins) {
											resultMsg += "�ù��� �̰���ϴ�\r\n";
										}
										else {
											resultMsg += "�ù��� �����ϴ�\r\n";							
										}						
									}
									resultMsg += "����� ������ �� " + part.wins + "�� �Դϴ�\r\n";
									if (game.broadMsg != null) resultMsg += "\r\n��� �޽���: " + game.broadMsg;
									if (part.privateMsg != null) resultMsg += "\r\n���� �޽���: " + part.privateMsg;
									resultMsg += "\r\n��ü ����\r\n";
									for (Entry<String, Participant> e : game.participantsMap.entrySet()) {
										resultMsg += e.getValue().decoded_nickname + "(����:" + e.getValue().wins + ") -> " + e.getValue().oriCharType.getName() + "\r\n";
									}					
									resultMsg += "\r\n���� ���\r\n" + game.logMsg;
								}
								else {
									mGameLock.unlock();
									System.out.println(String.format("system error, email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																												
								}
								mGameLock.unlock();

								System.out.println(resultMsg);
								System.out.println();
								
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity(resultMsg, ContentType.create("text/plain", "UTF-8"));
								response.setEntity(nsEntity);
								return;									
							}
							
							if(url.equalsIgnoreCase("/Choice")) {
								System.out.println("POST /Choice");

								String email = null;
								String sessionKey = null;
								String roomName = null;
								String choice = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
					                else if(key.equalsIgnoreCase("room_name")) {
					                	roomName = value;
					                }									
					                else if(key.equalsIgnoreCase("choice")) {
					                	choice = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}

								mGameLock.lock();
								Game game = mGameMap.get(roomName);
								if(game == null) {
									mGameLock.unlock();
									System.out.println(String.format("no room email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=no_room"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																		
								}
								
								Participant part = game.participantsMap.get(email);
								if(part == null) {
									mGameLock.unlock();
									System.out.println(String.format("system error, email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																											
								}
								else if (part.done) {
									mGameLock.unlock();
									System.out.println(String.format("system error, email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																											
								} 
								else if (game.status == GameStatus.NIGHT_FINISHED || game.status == GameStatus.DAY_FINISHED) {
									part.done = true;

									game.checkVoteDone();

									mGameLock.unlock();
									System.out.println("Vote " + part.decoded_nickname);
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=reload"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																											
								}

								Participant p1 = game.participantsMap.get(choice);
								if (p1 == null) {
									mGameLock.unlock();
									System.out.println(String.format("choice failed, email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																											
								} 

								System.out.println("Vote " + part.decoded_nickname + " -> " + p1.decoded_nickname);

								if (game.status == GameStatus.NIGHT) {
									switch (part.charType) {
									case DETECTIVE:
										part.privateMsg += p1.decoded_nickname + "�� ��ü�� " + p1.charType.getName() + "�Դϴ�\r\n";
										game.logMsg += "(Ž��)" + part.decoded_nickname + " -����-> (" + p1.charType.getName() + ")" + p1.decoded_nickname + "\r\n";   
										break;
									case DOCTOR:
										p1.savedBy = part;
										game.logMsg += "(�ǻ�)" + part.decoded_nickname + " -ġ��-> (" + p1.charType.getName() + ")" + p1.decoded_nickname + "\r\n";   
										break;
									case MAFIA:
										p1.voted++;
										game.logMsg += "(���Ǿ�)" + part.decoded_nickname + " -�ϻ�-> (" + p1.charType.getName() + ")" + p1.decoded_nickname + "\r\n";   
										break;
									default:
										break;
									}
									part.done = true;
								} else if (game.status == GameStatus.DAY) {
									p1.voted++;
									game.logMsg += "(" + part.charType.getName() + ")" + part.decoded_nickname + " -> (" + p1.charType.getName() + ")" + p1.decoded_nickname + "\r\n";
									part.done = true;
								} else {
									mGameLock.unlock();
									System.out.println(String.format("wrong request, email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																											
								} 

								game.checkVoteDone();

								mGameLock.unlock();
								System.out.println("Vote " + part.decoded_nickname);
								System.out.println();
								
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity(String.format("result=reload"), ContentType.create("text/plain", "UTF-8"));
								response.setEntity(nsEntity);
								return;																											
							}							
							if(url.equalsIgnoreCase("/Out")) {
								System.out.println("POST /Out");

								String email = null;
								String sessionKey = null;
								String roomName = null;
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
					                else if(key.equalsIgnoreCase("room_name")) {
					                	roomName = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}

								mGameLock.lock();
								Game game = mGameMap.get(roomName);
								if(game == null) {
									mGameLock.unlock();
									System.out.println(String.format("no room email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=no_room"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																		
								}
								
								if(game.owner.equalsIgnoreCase(email)) {
									mGameMap.remove(roomName);
								}
								else {
									game.removePlayer(email);									
								}
								mGameLock.unlock();

								System.out.println();
								
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity("result=done", ContentType.create("text/plain", "UTF-8"));
								response.setEntity(nsEntity);
								return;									
							}				
							
							if(url.equalsIgnoreCase("/StartGame")) {
								System.out.println("POST /StartGame");
								
								String email = null;
								String sessionKey = null;
								String roomName = null;
								
								for(String e : decodedEntityToken) {
									e = URLDecoder.decode(e, "UTF-8");
					                int i = e.indexOf("=");
					                if(i < 0) {
					                	break;
					                }
					                String key = e.substring(0, i);
					                String value = e.substring(i + 1).trim();
					                if(key.equalsIgnoreCase("email")) {
					                	email = value;
					                }									
					                else if(key.equalsIgnoreCase("session_key")) {
					                	sessionKey = value;
					                }									
					                else if(key.equalsIgnoreCase("room_name")) {
					                	roomName = value;
					                }									
								}
								if(email == null || mUserSessionKeyMap.get(email) == null || !mUserSessionKeyMap.get(email).equals(sessionKey)) {
									System.out.println(String.format("no email(%s) or wrong session key(%s)", email, sessionKey));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=wrong_session_key"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;									
								}
								
								mGameLock.lock();
								Game game = mGameMap.get(roomName);
								if(game == null) {
									mGameLock.unlock();
									System.out.println(String.format("no room email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=no_room"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																		
								}

								if(!game.owner.equalsIgnoreCase(email)) {
									mGameLock.unlock();
									System.out.println(String.format("not your room email=%s, room=%s", email, roomName));
									System.out.println();
									
									response.setStatusCode(HttpStatus.SC_OK);
									NStringEntity nsEntity = new NStringEntity(String.format("result=error"), ContentType.create("text/plain", "UTF-8"));
									response.setEntity(nsEntity);
									return;																		
								}								
								
								if(game.status == GameStatus.INITAL) {
									
									int totalPlayer = game.participantsMap.size();
									if(totalPlayer < 3) { 
										mGameLock.unlock();
										System.out.println(String.format("too few, email=%s, room=%s", email, roomName));
										System.out.println();
										
										response.setStatusCode(HttpStatus.SC_OK);
										NStringEntity nsEntity = new NStringEntity(String.format("result=few"), ContentType.create("text/plain", "UTF-8"));
										response.setEntity(nsEntity);
										return;																		
									}

									int remain = totalPlayer;
									game.characterQuotaMap.clear();
									
									if(remain >= totalPlayer / 3) {
										game.characterQuotaMap.put(CharacterType.MAFIA, totalPlayer / 3);
										remain -= totalPlayer / 3;
									}
									else {
										game.characterQuotaMap.put(CharacterType.MAFIA, 0);										
									}

									if(remain >= totalPlayer / 5) {
										game.characterQuotaMap.put(CharacterType.DOCTOR, totalPlayer / 5);
										remain -= totalPlayer / 5;
									}
									else {
										game.characterQuotaMap.put(CharacterType.DOCTOR, 0);										
									}

									if(remain >= totalPlayer / 6) {
										game.characterQuotaMap.put(CharacterType.DETECTIVE, totalPlayer / 6);
										remain -= totalPlayer / 6;
									}
									else {
										game.characterQuotaMap.put(CharacterType.DETECTIVE, 0);										
									}

									if(remain >= totalPlayer / 7) {
										game.characterQuotaMap.put(CharacterType.SOLDIER, totalPlayer / 7);
										remain -= totalPlayer / 7;
									}
									else {
										game.characterQuotaMap.put(CharacterType.SOLDIER, 0);										
									}

									System.out.println(String.format("owner=%s, room=%s", email, roomName));
									
									Vector<CharacterType> v = new Vector<CharacterType>();
									for (Entry<CharacterType, Integer> e : game.characterQuotaMap.entrySet()) {
										System.out.println(e.getKey().getName() + "=" + e.getValue().intValue());
										for (int i1 = 0; i1 < e.getValue().intValue(); i1++) {
											v.addElement(e.getKey());
										}
									}
									while (v.size() < game.participantsMap.size()) {
										System.out.println(CharacterType.CIVILIAN.getName() + "++");
										v.addElement(CharacterType.CIVILIAN);
									}
									for (Entry<String, Participant> e : game.participantsMap.entrySet()) {
										int rIndex = (int) (Math.random() * v.size());
										e.getValue().oriCharType = e.getValue().charType = v.elementAt(rIndex);
										System.out.println(e.getKey() + " -> " + e.getValue().charType.getName());
										v.removeElementAt(rIndex);
										e.getValue().init();

										// ���ο�, ���� ���� �ÿ��� �ʱ�ȭ
										e.getValue().tried = false;
									}

									if(game.policy_startFromDay) { 
										game.status = GameStatus.DAY;
										game.days = 1;
										game.logMsg += "[1���� �� �ù���ǥ ����]\r\n";
										System.out.println("game.status -> GameStatus.DAY");
									}
									else {
										game.status = GameStatus.NIGHT;
										game.days = 1;
										game.logMsg += "[1���� �� ���Ǿ���ǥ ����]\r\n";
										System.out.println("game.status -> GameStatus.NIGHT");							
									}
								}
								mGameLock.unlock();

								System.out.println();
								
								response.setStatusCode(HttpStatus.SC_OK);
								NStringEntity nsEntity = new NStringEntity("result=reload", ContentType.create("text/plain", "UTF-8"));
								response.setEntity(nsEntity);
								return;									
							}							
						}
					}
				}
			}

			{
				System.out.println(String.format("METHOD : %d", methodType));
				System.out.println(String.format("URL : %s", url));
				System.out.println(String.format("HEADERS"));
				for(Header h : request.getAllHeaders()) {
					System.out.println(String.format("    HEADER : %s, %s", h.getName(), h.getValue()));
					for(HeaderElement e : h.getElements()) {
						System.out.println(String.format("        HEADERELEMENTS : %s, %s", e.getName(), e.getValue()));
					}
				}
				if (request instanceof HttpEntityEnclosingRequest) {
					HttpEntity hEntity = ((HttpEntityEnclosingRequest) request).getEntity();
					if (hEntity != null) {
						System.out.println(String.format("BODY"));						
						String decodedEntityToken[] = new String(EntityUtils.toByteArray(hEntity)).split("\r\n");
						for(String e : decodedEntityToken) {
							e = URLDecoder.decode(e, "UTF-8");
							System.out.println(String.format("    BODYENTITIY: %s", e));						
						}
					}
				}
				System.out.println();
			}
			
			response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
			return;			
		}
	}
	
	public static void main2(String args[]) throws Exception {
	
		try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream(PASSWORD_FILE_NAME))) {
			Object fileObj = ois.readObject();
			if(fileObj != null && fileObj instanceof Map<?, ?>) mUserPasswordMap = (Map<String, String>) fileObj;			
		}
		catch(FileNotFoundException e) {
			System.out.println(String.format(PASSWORD_FILE_NAME + " file not found"));
		}
		if(mUserPasswordMap == null) mUserPasswordMap = new HashMap<String, String>();
		
		try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream(NICKNAME_FILE_NAME))) {
			Object fileObj = ois.readObject();
			if(fileObj != null && fileObj instanceof Map<?, ?>) mUserNicknameMap = (Map<String, String>) fileObj;			
		}
		catch(FileNotFoundException e) {
			System.out.println(String.format(NICKNAME_FILE_NAME + " file not found"));
		}
		if(mUserNicknameMap == null) mUserNicknameMap = new HashMap<String, String>();

		new Thread(new Runnable() {
			@Override
			public void run() {
				try(Scanner scanner = new Scanner(System.in)) {
					while(true) {
						if(scanner.hasNextLine()) {
							String lineStr = scanner.nextLine();
							lineStr = lineStr.trim();
							String[] tokens = lineStr.split(" ");
							tokens[0] = tokens[0].toLowerCase();
							if(lineStr.equalsIgnoreCase("h") || lineStr.equalsIgnoreCase("help") || lineStr.equals("?")) {
								System.out.println("vu.View users");
								System.out.println("cp.Change PW");
								System.out.println("du.Delete user");
								System.out.println("lg.List games");
								System.out.println("dg.Delete game");
								System.out.println("save.Save game data");
								System.out.println("load.Load game data");
							}
							else if(tokens[0].equals("vu")) {
								String s = ""; String e1;
								s += (e1 = "E-mail"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
								s += (e1 = "Nickname"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
								s += (e1 = "Password"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
								System.out.println(s);
								for(Entry<String, String> e : mUserPasswordMap.entrySet()) {
									s = "";
									s += (e1 = e.getKey()); for(int w = 0; w + e1.length() < 32; w++) s += " ";
									s += (e1 = mUserNicknameMap.get(e.getKey())); for(int w = 0; w + e1.length() < 32; w++) s += " ";
									s += (e1 = e.getValue()); for(int w = 0; w + e1.length() < 32; w++) s += " ";
									System.out.println(s);
								}
							}
							else if(tokens[0].equals("cp") && tokens.length == 3) {
								mUserPasswordLock.lock();
								mUserNickNameLock.lock();
								
								String pw = mUserPasswordMap.get(tokens[1].trim());
								if(pw != null && tokens[2].length() > 0) {
									
									mUserPasswordMap.put(tokens[1].trim(), tokens[2].trim());
									mUserSessionKeyMap.remove(tokens[1].trim());
									
									try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(PASSWORD_FILE_NAME))) {
										oos.writeObject(mUserPasswordMap);
									} catch (FileNotFoundException e) {
										e.printStackTrace();
									} catch (IOException e) {
										e.printStackTrace();
									}
								}
								
								mUserPasswordLock.unlock();
								mUserNickNameLock.unlock();

								System.out.println("done");
							}
							else if(tokens[0].equals("du") && tokens.length == 2) {
								mUserPasswordLock.lock();
								mUserNickNameLock.lock();

								if(tokens[1].trim().equalsIgnoreCase("all")) {
									mUserPasswordMap.clear();
									mUserNicknameMap.clear();
									mUserSessionKeyMap.clear();									
								}	
								else {
									mUserPasswordMap.remove(tokens[1].trim());
									mUserNicknameMap.remove(tokens[1].trim());
									mUserSessionKeyMap.remove(tokens[1].trim());
								}
								
								try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(PASSWORD_FILE_NAME))) {
									oos.writeObject(mUserPasswordMap);
								} catch (FileNotFoundException e) {
									e.printStackTrace();
								} catch (IOException e) {
									e.printStackTrace();
								}
								
								try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(NICKNAME_FILE_NAME))) {
									oos.writeObject(mUserNicknameMap);
								} catch (FileNotFoundException e) {
									e.printStackTrace();
								} catch (IOException e) {
									e.printStackTrace();
								}
								
								mUserPasswordLock.unlock();
								mUserNickNameLock.unlock();

								System.out.println("done");
							}
							else if(tokens[0].equals("lg")) {
								mGameLock.lock();
								String gameName = lineStr.substring(1).trim();
								if(gameName.length() > 0) {
									Game game = mGameMap.get(gameName);
									if(game != null) {
										System.out.println(String.format("Owner: %s", game.owner));
										System.out.println(String.format("Status: %s", game.status.toString()));
										System.out.println(String.format("Password: %s", game.password));
										System.out.println(String.format("Days: %d", game.days));
										System.out.println("[Participants]");
										
										String s = "    "; String e1;
										s += (e1 = "E-mail"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
										s += (e1 = "Nickname"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
										s += (e1 = "CharType"); for(int w = 0; w + e1.length() < 20; w++) s += " ";
										s += (e1 = "Voted"); for(int w = 0; w + e1.length() < 6; w++) s += " ";
										s += (e1 = "Wins"); for(int w = 0; w + e1.length() < 6; w++) s += " ";
										System.out.println(s);
										for(Entry<String, Participant> e : game.participantsMap.entrySet()) {
											s = "    ";
											s += (e1 = e.getKey()); for(int w = 0; w + e1.length() < 32; w++) s += " ";
											s += (e1 = e.getValue().decoded_nickname); for(int w = 0; w + e1.length() < 32; w++) s += " ";
											s += (e1 = e.getValue().charType.toString()); for(int w = 0; w + e1.length() < 20; w++) s += " ";
											s += (e1 = e.getValue().done ? "TRUE" : "FALSE"); for(int w = 0; w + e1.length() < 6; w++) s += " ";
											s += (e1 = String.valueOf(e.getValue().wins)); for(int w = 0; w + e1.length() < 6; w++) s += " ";
											System.out.println(s);
										}
										System.out.println("[LogMsg]");
										System.out.println(game.logMsg);
									}
									else {
										System.out.println("no game like that");										
									}
								}
								else {
									String s = ""; String e1;
									s += (e1 = "RoomName"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
									s += (e1 = "Owner"); for(int w = 0; w + e1.length() < 32; w++) s += " ";
									s += (e1 = "Status"); for(int w = 0; w + e1.length() < 20; w++) s += " ";
									s += (e1 = "NOW"); for(int w = 0; w + e1.length() < 6; w++) s += " ";
									s += (e1 = "MAX"); for(int w = 0; w + e1.length() < 6; w++) s += " ";
									System.out.println(s);
									for(Entry<String, Game> e : mGameMap.entrySet()) {
										s = "";
										s += (e1 = e.getValue().decoded_name); for(int w = 0; w + e1.length() < 32; w++) s += " ";
										s += (e1 = e.getValue().owner); for(int w = 0; w + e1.length() < 32; w++) s += " ";
										s += (e1 = e.getValue().status.toString()); for(int w = 0; w + e1.length() < 20; w++) s += " ";
										s += (e1 = String.valueOf(e.getValue().participantsMap.size())); for(int w = 0; w + e1.length() < 6; w++) s += " ";
										s += (e1 = String.valueOf(e.getValue().maxPlayers)); for(int w = 0; w + e1.length() < 6; w++) s += " ";
										System.out.println(s);
									}
								}
								mGameLock.unlock();
							}
							else if(tokens[0].equals("dg") && tokens.length == 2) {
								mGameLock.lock();
								if(tokens[1].trim().equalsIgnoreCase("all")) {
									mGameMap.clear();
									System.out.println("done");
								}
								else {
									for(Entry<String, Game> e : mGameMap.entrySet()) {
										if(e.getValue().decoded_name.equals(tokens[1].trim())) {
											mGameMap.remove(e.getKey());
											System.out.println("done");
										}
										else {
											System.out.println("no such a room");											
										}
									}
									mGameMap.remove(tokens[1].trim());
								}
								mGameLock.unlock();
							}
							else if(tokens[0].equals("save")) {
								String name = "";
								if(tokens.length == 2 && tokens[1].length() > 0) {
									name = "." + tokens[1];
								}
								try(ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(String.format(GAMEDATA_FILE_NAME, name)))) {
									oos.writeObject(mGameMap);
								} 
								catch (FileNotFoundException e1) {
									e1.printStackTrace();
								} 
								catch (IOException e1) {
									e1.printStackTrace();
								}

								System.out.println("done");
							}
							else if(tokens[0].equals("load")) {
								String name = "";
								if(tokens.length == 2 && tokens[1].length() > 0) {
									name = "." + tokens[1];
								}
								try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream(String.format(GAMEDATA_FILE_NAME, name)))) {
									Object fileObj = ois.readObject();
									if(fileObj != null && fileObj instanceof Map<?, ?>) mGameMap = (Map<String, Game>) fileObj;
								}
								catch(FileNotFoundException e1) {
									System.out.println(String.format(String.format(GAMEDATA_FILE_NAME, name) + " file not found"));
								} 
								catch (IOException e1) {
									e1.printStackTrace();
								} 
								catch (ClassNotFoundException e1) {
									e1.printStackTrace();
								}

								System.out.println("done");
							}
						}
					}
				}
			}
		}).start();
		
		// Create HTTP protocol processing chain
		HttpProcessor httpproc = HttpProcessorBuilder.create()
				.add(new ResponseDate())
				.add(new ResponseServer("MafiaGameAndroid/1.0"))
				.add(new ResponseContent()).add(new ResponseConnControl())
				.build();

		// Create request handler registry
		UriHttpAsyncRequestHandlerMapper reqistry = new UriHttpAsyncRequestHandlerMapper();

		// Register the default handler for all URIs
		reqistry.register("/*", new HttpHandler());

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

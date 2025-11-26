package com.metacoding.sse.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.metacoding.sse.chat.Chat;

import lombok.extern.slf4j.Slf4j;

/**
 * SSE(Server-Sent Events) 연결을 관리하는 레지스트리 클래스.
 *
 * - 클라이언트가 "/api/chats/conect" SSE 엔드포인트에 접속하면, 서버는 SseEmitter 객체를 하나 생성한다. -
 * SseEmitters는 이 SseEmitter 들을 모두 모아서 보관하고, 새 채팅 메시지가 생길 때마다 "sendAll" 메서드로 전체
 * 클라이언트에게 브로드캐스트한다.
 *
 * 이 클래스는 "한 서버 인스턴스 안에서 열려 있는 모든 SSE 연결"에 대한 in-memory 레지스트리 역할을 한다고 생각하면 된다.
 */
@Slf4j
@Component
public class SseEmitters {

    /**
     * ConcurrentHashMap란? 현재 서버에 연결된 SSE 클라이언트 목록을 저장하는 맵. {key, value}
     *
     * key : clientId (보통 HttpSession ID, 또는 사용자 고유 ID 등), value : 해당 클라이언트와 연결된
     * SseEmitter 인스턴스
     *
     * ConcurrentHashMap 을 사용하는 이유: - 여러 쓰레드에서 동시에 읽기/쓰기(추가, 삭제, 조회)가 일어날 수 있기 때문.
     *
     * 예) A 사용자가 접속해서 emitter 등록 중일 때, B 사용자에게 메시지를 push 하는 sendAll() 이 동시에 동작할 수있음.
     *
     * - 일반 HashMap 은 멀티쓰레드 환경에서 ConcurrentModificationException 이 발생할 수 있으므로 스레드
     * 세이프한 ConcurrentHashMap을 사용한다.
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 새로운 SSE 연결을 등록한다.
     *
     * 내부 동작 요약 1) 동일 clientId 로 이미 등록된 emitter 가 있다면, 이전 연결을 먼저 종료한다. - 브라우저에서
     * F5(새로고침)를 눌렀을 때 같은 세션 ID로 다시 접속하는 경우가 대표적이다.
     *
     * 2) emitters 맵에 (clientId -> emitter) 형태로 현재 emitter 를 저장한다.
     *
     * 3) emitter의 콜백(onCompletion, onTimeout,onError)을 등록해서 연결이 끝났을 때 emitters 맵에서
     * clean-up 되도록 한다.
     */
    public SseEmitter add(String clientId, SseEmitter emitter) {

        // 1. 동일 clientId 가 이미 존재하는 경우
        // - 기존 SSE 연결이 살아 있는 상태에서 새로고침 등으로 다시 접속한 상황.
        // - 이전 emitter 를 명시적으로 complete()시켜 연결을 끊어준다.
        if (emitters.containsKey(clientId)) {
            SseEmitter prevEmitter = emitters.get(clientId);
            prevEmitter.complete();
            log.info("prev emitter removed for clientId: {}", clientId);
        }

        // 2. 새 emitter 등록
        emitters.put(clientId, emitter);
        log.info("new emitter clientId: {}", clientId);
        log.info("new emitter added: {}", emitter);
        log.info("emitter list size: {}", emitters.size());

        /*
         * 3. emitter 생명주기 콜백 등록
         *
         * SseEmitter 는 아래 세 가지 상황에서 콜백을 호출할 수 있다.
         *
         * - onCompletion : 서버 또는 클라이언트가 스트림을 정상 종료한 경우
         *
         * - onTimeout : 지정한 타임아웃 동안 아무 데이터도 보내지 않아서 타임아웃이 발생한 경우
         *
         * - onError : 네트워크 오류 등 비정상적인 예외가 발생한 경우
         *
         * 이 세 콜백이 호출된 뒤에는 더 이상 이 emitter 에 이벤트를 보내면 안 되므로, emitters 맵에서 해당 clientId 키를
         * 제거해 준다.
         * 
         * 
         */

        final SseEmitter currentEmitter = emitter;

        // (1) 정상 완료 또는 타임아웃 후 호출되는 콜백
        emitter.onCompletion(() -> {
            log.info("SSE 연결 종료됨 (clientId: {})", clientId);
            // 이전 emitter가 늦게 complete되더라도 최신 emitter는 지우지 않도록 동일 인스턴스일 때만 제거
            emitters.remove(clientId, currentEmitter);
        });

        // (2) 타임아웃 발생 시 호출
        // - 일정 시간 동안 서버 → 클라이언트로 이벤트를 보내지 못하면 타임아웃이 발생한다.
        emitter.onTimeout(() -> {
            log.info("SSE 연결 타임아웃 발생 (clientId: {})", clientId);
            emitter.complete();
        });

        // (3) 오류 발생 시 콜백
        // - 네트워크 끊김, 탭/브라우저 종료, 인터넷 불안정 등 다양한 원인으로 발생할 수 있다.
        emitter.onError((e) -> {
            log.error("SSE 전송 중 오류 발생 (clientId: {})", clientId, e);
            emitter.complete();
        });

        return emitter;
    }

    /**
     * 현재 서버에 연결된 모든 SSE 클라이언트에게 채팅 메시지를 푸시(Push)한다.
     *
     * <동작 구조> - emitters(Map<String, SseEmitter>)는 서버에 접속해 있는 모든 클라이언트를 저장한다. key →
     * clientId (세션ID 또는 사용자 고유 ID) value → 해당 클라이언트와 연결된 SseEmitter 객체
     *
     * - sendAll()은 emitters.entrySet()을 순회하면서 각 클라이언트에게 "chat"이라는 이름의 SSE 이벤트를
     * 전송한다.
     *
     * <SSE 프로토콜로 실제 전송되는 데이터 예시> event: chat data: {"id":1,"message":"안녕"} ← Chat
     * 객체가 JSON으로 직렬화됨
     *
     * <entrySet()을 사용하는 이유> - Map을 순회할 때는 key와 value(즉 clientId와 emitter)를 동시에 얻어야
     * 한다. - entrySet()은 (clientId, emitter) 쌍을 한 번에 가져오므로 가장 효율적이며, getKey(),
     * getValue() 사용이 가능하다.
     */
    public void sendAll(Chat chat) {
        emitters.entrySet().forEach(entry -> {

            String clientId = entry.getKey();
            SseEmitter emitter = entry.getValue();

            try {
                emitter.send(SseEmitter.event().name("chat") // 클라이언트에서 수신할 이벤트 이름
                        .data(chat) // Chat 객체는 JSON 으로 직렬화되어 내려간다.
                );
            } catch (Exception e) {
                // 보내는 도중 예외가 발생했다는 것은
                // - 해당 클라이언트와의 연결이 이미 끊겼거나
                // - 네트워크 오류가 발생했다는 의미다.

                // 1) emitter는 이미 끊긴 상태 → 정리
                emitter.complete();
                emitters.remove(clientId);

                // 2) RuntimeException 던짐
                throw new RuntimeException("SSE 메시지 전송 실패 - clientId=" + clientId, e);
            }
        });
    }

}

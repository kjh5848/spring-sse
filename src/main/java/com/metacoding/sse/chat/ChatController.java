package com.metacoding.sse.chat;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.metacoding.sse.config.SseEmitters;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Controller
public class ChatController {

    private final ChatService chatService;
    private final SseEmitters sseEmitters;
    private final HttpSession session;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * SSE 연결 엔드포인트 클라이언트(브라우저, Postman 등)가 SSE를 수신하기 위해 접속하는 주소.
     *
     * 이 연결이 열리면 서버는 이벤트를 계속 push 할 수 있게 된다.
     */
    @GetMapping(value = "/chats/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect() {

        String clientId = session.getId();

        // SseEmitter 생성 (1분동안 서버에서 이벤트를 보내지 않으면 타임아웃)
        SseEmitter emitter = new SseEmitter(60 * 1000L);

        // SseEmitter를 서버 저장소(Map)에 등록
        sseEmitters.add(clientId, emitter);

        try {
            // Emmitter 생성 후 1분동안 아무런 데이터도 브라우저에 보내지 않으면
            // 브라우저 측에서 재연결 요청시에 403 Service Unavailable 에러 발생함
            // 이를 방지 하기 위해서 data에 더미를 보냄.
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (Exception e) {
            emitter.complete();
        }

        return ResponseEntity.ok(emitter);
    }

    /**
     * 메시지 저장
     */
    @PostMapping("/chats")
    @ResponseBody
    public Chat save(@RequestBody ChatRequest req) {
        Chat saved = chatService.save(req);

        // 저장되자마자 SSE 전체 전송
        sseEmitters.sendAll(saved);

        return saved;
    }

    /**
     * 메시지 조회
     */
    @GetMapping("/chats")
    @ResponseBody
    public List<Chat> list() {
        return chatService.findAll();
    }

}
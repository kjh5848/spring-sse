package com.metacoding.sse.chat;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class ChatService {
    private final ChatRepository chatRepository;

    @Transactional
    public Chat save(ChatRequest req) {
        Chat chat = Chat.builder().message(req.message()).build();
        return chatRepository.save(chat);
    }

    public List<Chat> findAll() {
        Sort desc = Sort.by(Sort.Direction.DESC, "id");
        return chatRepository.findAll(desc);
    }
}

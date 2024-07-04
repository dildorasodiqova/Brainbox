package it.live.brainbox.utils;

import it.live.brainbox.mapper.SubtitleMapper;
import it.live.brainbox.payload.Pronunciation;
import it.live.brainbox.payload.Result;
import it.live.brainbox.payload.SubtitleWordPyDTO;
import it.live.brainbox.payload.WordDefinitionApiDTO;
import it.live.brainbox.repository.SubtitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;


@Service
@RequiredArgsConstructor
public class WordDefinitionApi {
    private final SubtitleMapper subtitleMapper;
    private final SubtitleRepository subtitleRepository;
    @Value("${fast.url}")
    private String fastApiUrl;
    @Value("${wordsapi.url}")
    private String wordApiUrl;
    @Value("${wordsapi.hack}")
    private String wordApiHack;

    private final RestTemplate restTemplate;

    public boolean addSubtitle(Long movieId, MultipartFile file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("subtitle_file", new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            });

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            Result apiResponse = restTemplate.postForEntity(
                    fastApiUrl + "/uploadSubtitle", requestEntity, Result.class).getBody();

            assert apiResponse != null;

            // Yuborilgan so'zni tekshirish uchun qo'shildi
            Set<String> sentWords = new HashSet<>();

            List<CompletableFuture<Void>> futureTasks = apiResponse.getResult().stream()
                    .filter(subtitleWordPyDTO -> sentWords.add(subtitleWordPyDTO.getWord())) // Faqat bitta marta yuborish uchun
                    .map(subtitleWordPyDTO -> processSubtitleWordAsync(movieId, subtitleWordPyDTO))
                    .toList();

            CompletableFuture<Void> allOf = CompletableFuture.allOf(futureTasks.toArray(new CompletableFuture[0]));
            allOf.join();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    private CompletableFuture<Void> processSubtitleWordAsync(Long movieId, SubtitleWordPyDTO subtitleWordPyDTO) {
        return CompletableFuture.runAsync(() -> {
            try {
                String word = subtitleWordPyDTO.getWord();
                Pronunciation pronunciation = restTemplate.getForObject(
                        wordApiUrl + word + "/pronunciation?when=2023-11-12T18:45:37.334Z&encrypted=" + wordApiHack, Pronunciation.class);
                subtitleRepository.save(subtitleMapper.toEntity(subtitleWordPyDTO, pronunciation, movieId));
            } catch (Exception e) {
                subtitleRepository.save(subtitleMapper.toEntity(subtitleWordPyDTO, null, movieId));
            }
        });
    }
}

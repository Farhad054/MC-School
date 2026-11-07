package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.lessons.PersonalLessonWorkbookService;
import com.mcschool.flashcard.users.User;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KolinTaskRoster {
    public record Pupil(UUID id,String name) {}
    private final PersonalLessonWorkbookService personal;
    public KolinTaskRoster(PersonalLessonWorkbookService personal) {
        this.personal=personal;
    }
    @Transactional(readOnly=true)
    public List<Pupil> read(OnlineClass room) {
        return personal.studentsForClass(room).stream().limit(50)
                .map(user -> new Pupil(user.getId(),user.getFullName())).toList();
    }
}

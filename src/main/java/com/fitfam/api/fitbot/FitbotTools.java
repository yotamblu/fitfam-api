package com.fitfam.api.fitbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fitfam.api.training.ExerciseService;
import com.fitfam.api.training.PaceService;
import com.fitfam.api.training.ProgressService;
import com.fitfam.api.training.StatsService;
import com.fitfam.api.training.StepExpander;
import com.fitfam.api.training.TrainingDtos.CustomerWorkoutDto;
import com.fitfam.api.training.TrainingDtos.ExerciseDto;
import com.fitfam.api.training.TrainingDtos.MyPlanDto;
import com.fitfam.api.training.TrainingDtos.RoadmapDto;
import com.fitfam.api.training.TrainingDtos.RoadmapLevelDto;
import com.fitfam.api.training.TrainingDtos.RoadmapWorkoutDto;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What Fitbot can look up. Every tool is read-only and runs as the signed-in customer: the user id comes from the
 * session, never from the model, and workouts are opened through the same rules the app itself uses (a locked workout
 * stays locked). Results are plain Hebrew text so the model can quote them, and are size-capped.
 */
@Component
public class FitbotTools {

	private static final int MAX_TEXT = 6000;

	private final ProgressService progress;
	private final StatsService stats;
	private final PaceService paces;
	private final ExerciseService exercises;
	private final StepExpander expander;
	private final ObjectMapper mapper;

	public FitbotTools(ProgressService progress, StatsService stats, PaceService paces, ExerciseService exercises,
			StepExpander expander, ObjectMapper mapper) {
		this.progress = progress;
		this.stats = stats;
		this.paces = paces;
		this.exercises = exercises;
		this.expander = expander;
		this.mapper = mapper;
	}

	public List<ChatModel.ToolSpec> specs() {
		return List.of(
				spec("get_my_progress", "ההתקדמות של המשתמש/ת: התוכניות והרמות שלו/ה, נקודות, מספר אימונים שהושלמו, רצף שבועי.",
						Map.of(), List.of()),
				spec("get_next_workout", "האימון הבא של המשתמש/ת בתוכנית, עם כל התוכן שלו (תרגילים, סטים, חזרות, מנוחות). אם אין plan_slug נבחרת התוכנית הראשונה.",
						Map.of("plan_slug", "מזהה תוכנית (slug) מתוך get_my_progress. אופציונלי."), List.of()),
				spec("get_workout", "תוכן של אימון מסוים לפי מזהה, רק אימון שהמשתמש/ת רשאי/ת לראות. המזהה מגיע מ-get_roadmap או get_next_workout.",
						Map.of("workout_id", "מזהה האימון"), List.of("workout_id")),
				spec("get_roadmap", "מפת הדרך של תוכנית: באיזה שלב המשתמש/ת, כמה אימונים הושלמו ברמה, והאימונים הקרובים.",
						Map.of("plan_slug", "מזהה תוכנית (slug). אופציונלי."), List.of()),
				spec("get_exercise", "הסבר על תרגיל ממאגר התרגילים של FitFam: איך מבצעים, טעויות נפוצות, ציוד. חיפוש לפי שם בעברית.",
						Map.of("name", "שם התרגיל או חלק ממנו, בעברית"), List.of("name")),
				spec("get_paces", "הקצבים האישיים לריצה (קל, סף, אינטרוולים, 5 ק״מ) שמחושבים מזמן ה-5 ק״מ של המשתמש/ת.",
						Map.of(), List.of()));
	}

	/** A runner bound to one customer for one message. {@code workouts} collects the ids it returned (for safe buttons). */
	public Session session(UUID userId) {
		return new Session(userId);
	}

	public final class Session implements ChatModel.ToolRunner {

		private final UUID userId;
		/** workout id -> title, for every workout a tool returned in this turn */
		final Map<String, String> workouts = new LinkedHashMap<>();

		private Session(UUID userId) {
			this.userId = userId;
		}

		public Map<String, String> workouts() {
			return workouts;
		}

		@Override
		public JsonNode run(String name, JsonNode args) {
			try {
				return switch (name) {
					case "get_my_progress" -> myProgress();
					case "get_next_workout" -> nextWorkout(args.path("plan_slug").stringValue());
					case "get_workout" -> workout(args.path("workout_id").stringValue());
					case "get_roadmap" -> roadmap(args.path("plan_slug").stringValue());
					case "get_exercise" -> exercise(args.path("name").stringValue());
					case "get_paces" -> pacesInfo();
					default -> error("unknown_tool");
				};
			}
			catch (ApiException e) {
				return error(e.getCode());
			}
		}

		private JsonNode myProgress() {
			List<MyPlanDto> plans = progress.myPlans(userId);
			StatsService.StatsDto s = stats.stats(userId);
			StringBuilder sb = new StringBuilder();
			if (plans.isEmpty()) {
				sb.append("אין למשתמש/ת תוכניות פעילות.\n");
			}
			for (MyPlanDto p : plans) {
				sb.append("תוכנית: ").append(p.planNameHe()).append(" (slug: ").append(p.planSlug()).append("), רמה ")
						.append(p.levelNumber());
				if (p.levelNameHe() != null) {
					sb.append(" - ").append(p.levelNameHe());
				}
				sb.append(", סטטוס: ").append(p.status()).append('\n');
			}
			sb.append("רמת חשבון ").append(s.level()).append(", ").append(s.xp()).append(" נקודות, ")
					.append(s.totalWorkouts()).append(" אימונים שהושלמו, ").append(s.challengesPassed())
					.append(" אתגרים שעברו. השבוע: ").append(s.weekCount()).append(" מתוך יעד ").append(s.weeklyGoal())
					.append(". רצף שבועי: ").append(s.weeklyStreak()).append(" שבועות.");
			return text(sb.toString());
		}

		private JsonNode nextWorkout(String planSlug) {
			RoadmapDto roadmap = roadmapFor(planSlug);
			if (roadmap.planCompleted()) {
				return text("המשתמש/ת סיים/ה את כל התוכנית " + roadmap.planNameHe() + ".");
			}
			for (RoadmapLevelDto level : roadmap.levels()) {
				for (RoadmapWorkoutDto w : level.workouts()) {
					if ("current".equals(w.state())) {
						return workout(w.id());
					}
				}
			}
			return text("לא נמצא אימון הבא בתוכנית " + roadmap.planNameHe() + ".");
		}

		private JsonNode workout(String id) {
			UUID workoutId;
			try {
				workoutId = UUID.fromString(id == null ? "" : id);
			}
			catch (IllegalArgumentException e) {
				return error("invalid_workout_id");
			}
			CustomerWorkoutDto w = progress.openWorkout(userId, workoutId);
			workouts.put(w.id(), w.titleHe() == null ? "אימון" : w.titleHe());
			StringBuilder sb = new StringBuilder();
			sb.append("מזהה אימון: ").append(w.id()).append('\n');
			sb.append("כותרת: ").append(w.titleHe()).append(" (סוג: ").append(typeHe(w.type())).append(", רמה ")
					.append(w.levelNumber()).append(", מצב: ").append(w.state()).append(")\n");
			if (w.estMinutes() != null) {
				sb.append("משך משוער: ").append(w.estMinutes()).append(" דקות\n");
			}
			if (w.goalHe() != null && !w.goalHe().isBlank()) {
				sb.append("מטרה: ").append(w.goalHe()).append('\n');
			}
			if (w.descriptionHe() != null && !w.descriptionHe().isBlank()) {
				sb.append("תיאור: ").append(w.descriptionHe()).append('\n');
			}
			sb.append(render(expander.expand(w.content(), paces.fiveKSec(userId))));
			return text(sb.toString());
		}

		private JsonNode roadmap(String planSlug) {
			RoadmapDto roadmap = roadmapFor(planSlug);
			StringBuilder sb = new StringBuilder();
			sb.append("תוכנית: ").append(roadmap.planNameHe()).append(", רמה נוכחית ").append(roadmap.currentLevelNumber());
			if (roadmap.planCompleted()) {
				sb.append(" (התוכנית הושלמה)");
			}
			sb.append('\n');
			for (RoadmapLevelDto level : roadmap.levels()) {
				long done = level.workouts().stream().filter(w -> "done".equals(w.state())).count();
				sb.append("רמה ").append(level.levelNumber());
				if (level.nameHe() != null) {
					sb.append(" (").append(level.nameHe()).append(')');
				}
				sb.append(": ").append(done).append(" מתוך ").append(level.workouts().size()).append(" הושלמו, מצב ")
						.append(level.state()).append('\n');
				if (level.levelNumber() == roadmap.currentLevelNumber()) {
					int shown = 0;
					for (RoadmapWorkoutDto w : level.workouts()) {
						if ("done".equals(w.state())) {
							continue;
						}
						sb.append("  - ").append(w.titleHe()).append(" [").append(typeHe(w.type())).append(", ")
								.append(w.state()).append(", מזהה ").append(w.id()).append("]\n");
						if (++shown >= 5) {
							break;
						}
					}
				}
			}
			return text(sb.toString());
		}

		private JsonNode exercise(String query) {
			if (query == null || query.isBlank()) {
				return error("name_required");
			}
			List<ExerciseDto> found = exercises.list(null, query.trim(), false);
			if (found.isEmpty()) {
				return text("לא נמצא תרגיל בשם הזה במאגר של FitFam. נסה/י ניסוח אחר או ענה/י מהידע הכללי תוך ציון שזה לא מהמאגר.");
			}
			StringBuilder sb = new StringBuilder();
			for (ExerciseDto e : found.stream().limit(3).toList()) {
				sb.append("תרגיל: ").append(e.nameHe()).append(" (ענף: ").append(e.sport()).append(", נמדד ב: ")
						.append(e.measure()).append(")\n");
				if (e.equipment() != null && !e.equipment().isEmpty()) {
					sb.append("ציוד: ").append(String.join(", ", e.equipment())).append('\n');
				}
				if (e.descriptionHe() != null && !e.descriptionHe().isBlank()) {
					sb.append("איך מבצעים: ").append(e.descriptionHe()).append('\n');
				}
				if (e.cuesHe() != null && !e.cuesHe().isBlank()) {
					sb.append("טעויות נפוצות ודגשים: ").append(e.cuesHe()).append('\n');
				}
				sb.append('\n');
			}
			if (found.size() > 3) {
				sb.append("עוד תרגילים תואמים: ")
						.append(String.join(", ", found.stream().skip(3).limit(10).map(ExerciseDto::nameHe).toList()));
			}
			return text(sb.toString());
		}

		private JsonNode pacesInfo() {
			PaceService.PaceStatus status = paces.status(userId);
			if (status.fiveKSec() == null) {
				return text("המשתמש/ת עוד לא הזין/ה זמן 5 ק״מ, ולכן אין קצבים אישיים. אפשר להזין בדף הבית או בפרופיל.");
			}
			StringBuilder sb = new StringBuilder("זמן 5 ק״מ: ").append(clock(status.fiveKSec())).append('\n');
			for (PaceService.Pace p : status.paces()) {
				sb.append(p.labelHe()).append(": ").append(clock(p.fastSecPerKm())).append("-").append(clock(p.slowSecPerKm()))
						.append(" לק״מ. ").append(p.feelHe()).append('\n');
			}
			return text(sb.toString());
		}

		private RoadmapDto roadmapFor(String planSlug) {
			String slug = planSlug;
			if (slug == null || slug.isBlank()) {
				List<MyPlanDto> plans = progress.myPlans(userId);
				if (plans.isEmpty()) {
					throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "no_plan");
				}
				slug = plans.get(0).planSlug();
			}
			return progress.roadmap(userId, slug);
		}
	}

	// ---- rendering ----

	/** A compact, readable list of what the customer will do (sets folded, rests attached to the line they follow). */
	String render(List<StepExpander.Step> steps) {
		StringBuilder sb = new StringBuilder();
		String section = null;
		boolean restShown = true; // a rest is shown once after a line, not after every set
		for (StepExpander.Step step : steps) {
			if (sb.length() > MAX_TEXT) {
				sb.append("... (קוצר)");
				break;
			}
			if (!java.util.Objects.equals(section, step.sectionTitle())) {
				section = step.sectionTitle();
				sb.append("\n[").append(section == null ? "אימון" : section).append("]\n");
			}
			if (StepExpander.REST.equals(step.type())) {
				if (step.setNumber() == null || step.setNumber() <= 1) {
					sb.append("   מנוחה ").append(step.durationSec() == null ? "" : step.durationSec() + " שנ׳").append('\n');
				}
				continue;
			}
			if (step.setNumber() != null && step.setNumber() > 1) {
				continue; // a later set of the same line: the first one already says how many sets
			}
			if (StepExpander.TIMED_BLOCK.equals(step.type()) && step.targets() != null) {
				sb.append("- ").append(step.blockStyle());
				if (step.durationSec() != null) {
					sb.append(' ').append(step.durationSec() / 60).append(" דק׳");
				}
				sb.append(": ");
				List<String> parts = new ArrayList<>();
				for (StepExpander.Target t : step.targets()) {
					parts.add(target(t));
				}
				sb.append(String.join("; ", parts)).append('\n');
				continue;
			}
			if (step.target() != null) {
				sb.append("- ").append(target(step.target()));
				if (step.setCount() != null && step.setCount() > 1) {
					sb.append(" x ").append(step.setCount()).append(" סטים");
				}
				if (step.roundCount() != null && step.roundCount() > 1 && step.roundNumber() != null && step.roundNumber() == 1) {
					sb.append(" (").append(step.roundCount()).append(" סבבים)");
				}
				sb.append('\n');
			}
		}
		return sb.toString().strip();
	}

	private static String target(StepExpander.Target t) {
		StringBuilder sb = new StringBuilder();
		if (t.exercise() != null) {
			sb.append(t.exercise().nameHe());
		}
		if (t.reps() != null) {
			sb.append(' ').append(t.reps());
			if (t.repsMax() != null) {
				sb.append('-').append(t.repsMax());
			}
			sb.append(" חזרות");
		}
		if (t.durationSec() != null) {
			sb.append(' ').append(t.durationSec()).append(" שנ׳");
		}
		if (t.distanceM() != null) {
			sb.append(' ').append(t.distanceM()).append(" מ׳");
		}
		if (t.zone() != null) {
			sb.append(", אזור ").append(t.zone());
		}
		if (t.rpe() != null) {
			sb.append(", RPE ").append(t.rpe());
		}
		if (t.pace() != null && t.pace().fastSecPerKm() != null) {
			sb.append(", קצב ").append(t.pace().labelHe()).append(' ').append(clock(t.pace().fastSecPerKm())).append('-')
					.append(clock(t.pace().slowSecPerKm()));
		}
		if (t.loadHe() != null && !t.loadHe().isBlank()) {
			sb.append(", ").append(t.loadHe());
		}
		if (t.notesHe() != null && !t.notesHe().isBlank()) {
			sb.append(" (").append(t.notesHe()).append(')');
		}
		return sb.toString().strip();
	}

	private static String clock(Integer sec) {
		return sec == null ? "?" : String.format("%d:%02d", sec / 60, sec % 60);
	}

	private static String typeHe(String type) {
		return switch (type) {
			case "challenge" -> "אתגר מעבר רמה";
			case "checkpoint" -> "נקודת ביקורת";
			default -> "אימון רגיל";
		};
	}

	// ---- plumbing ----

	private JsonNode text(String text) {
		String cut = text.length() > MAX_TEXT + 500 ? text.substring(0, MAX_TEXT + 500) + "..." : text;
		return mapper.createObjectNode().put("text", cut);
	}

	private JsonNode error(String code) {
		return mapper.createObjectNode().put("error", code);
	}

	private ChatModel.ToolSpec spec(String name, String description, Map<String, String> properties, List<String> required) {
		ObjectNode schema = mapper.createObjectNode();
		schema.put("type", "OBJECT");
		ObjectNode props = schema.putObject("properties");
		properties.forEach((key, desc) -> {
			ObjectNode p = props.putObject(key);
			p.put("type", "STRING");
			p.put("description", desc);
		});
		if (!required.isEmpty()) {
			var req = schema.putArray("required");
			required.forEach(req::add);
		}
		// Gemini rejects an OBJECT schema with an empty properties map
		if (properties.isEmpty()) {
			return new ChatModel.ToolSpec(name, description, null);
		}
		return new ChatModel.ToolSpec(name, description, schema);
	}

}

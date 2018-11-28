package alfio.controller.api.admin;

import java.util.Arrays;
import java.util.List;

public class WaitingQueueDownloader {
    private static final List<String> FIXED_FIELDS = Arrays.asList("ID", "Creation", "Event", "Status", "Full Name", "First Name", "Last Name", "E-Mail", "Ticket Reservation Id", "Language", "Selected category", "Subscription type");

    public static List<String> availableFields() {
        return FIXED_FIELDS;
    }
}

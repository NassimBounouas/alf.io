package alfio.controller.api.admin;

import java.util.HashMap;
import java.util.Map;

public class WaitingQueueDownloader {
    private Map<String, String> map;

    public WaitingQueueDownloader() {
        map = new HashMap<>();
        map.put("id", "ID");
        map.put("creation", "Creation");
        map.put("event", "Event");
        map.put("status", "Status");
        map.put("fullname", "Full Name");
        map.put("firstname", "First Name");
        map.put("lastname", "Last Name");
        map.put("email", "E-Mail");
        map.put("ticket_reservation_id", "Ticket Reservation Id");
        map.put("language", "Language");
        map.put("selected_category", "Selected category");
        map.put("subscription_type", "Subscription type");
    }

    public Map<String, String> availableFields() {
        return this.map;
    }
}

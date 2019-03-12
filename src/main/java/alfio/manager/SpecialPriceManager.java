/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.manager.i18n.I18nManager;
import alfio.model.*;
import alfio.model.modification.SendCodeModification;
import alfio.model.user.Organization;
import alfio.repository.SpecialPriceRepository;
import alfio.util.TemplateManager;
import alfio.util.TemplateResource;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

@Component
public class SpecialPriceManager {

    private static final Predicate<SendCodeModification> IS_CODE_PRESENT = v -> Optional.ofNullable(v.getCode()).isPresent();
    private final EventManager eventManager;
    private final NotificationManager notificationManager;
    private final SpecialPriceRepository specialPriceRepository;
    private final TemplateManager templateManager;
    private final MessageSource messageSource;
    private final I18nManager i18nManager;

    @Autowired
    public SpecialPriceManager(EventManager eventManager,
                               NotificationManager notificationManager,
                               SpecialPriceRepository specialPriceRepository,
                               TemplateManager templateManager,
                               MessageSource messageSource,
                               I18nManager i18nManager) {
        this.eventManager = eventManager;
        this.notificationManager = notificationManager;
        this.specialPriceRepository = specialPriceRepository;
        this.templateManager = templateManager;
        this.messageSource = messageSource;
        this.i18nManager = i18nManager;
    }

    private List<String> checkCodeAssignment(Set<SendCodeModification> input, int categoryId, EventAndOrganizationId event, String username) {
        final TicketCategory category = checkOwnership(categoryId, event, username);
        List<String> availableCodes = specialPriceRepository.findActiveByCategoryId(category.getId())
            .stream()
            .filter(SpecialPrice::notSent)
            .map(SpecialPrice::getCode).collect(toList());
        Validate.isTrue(input.size() <= availableCodes.size(), "Requested codes: "+input.size()+ ", available: "+availableCodes.size()+".");
        List<String> requestedCodes = input.stream().filter(IS_CODE_PRESENT).map(SendCodeModification::getCode).collect(toList());
        Validate.isTrue(requestedCodes.stream().distinct().count() == requestedCodes.size(), "Cannot assign the same code twice. Please fix the input file.");
        Validate.isTrue(availableCodes.containsAll(requestedCodes), "some requested codes don't exist.");
        return availableCodes;
    }

    private TicketCategory checkOwnership(int categoryId, EventAndOrganizationId event, String username) {
        eventManager.checkOwnership(event, username, event.getOrganizationId());
        final List<TicketCategory> categories = eventManager.loadTicketCategories(event);
        final TicketCategory category = categories.stream().filter(tc -> tc.getId() == categoryId).findFirst().orElseThrow(IllegalArgumentException::new);
        Validate.isTrue(category.isAccessRestricted(), "Access to the selected category is not restricted.");
        return category;
    }

    public List<SendCodeModification> linkAssigneeToCode(List<SendCodeModification> input, String eventName, int categoryId, String username) {
        final EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, username);
        Set<SendCodeModification> set = new LinkedHashSet<>(input);
        List<String> availableCodes = checkCodeAssignment(set, categoryId, event, username);
        final Iterator<String> codes = availableCodes.iterator();
        return Stream.concat(set.stream().filter(IS_CODE_PRESENT),
                input.stream().filter(IS_CODE_PRESENT.negate())
                        .map(p -> new SendCodeModification(codes.next(), p.getAssignee(), p.getEmail(), p.getLanguage()))).collect(toList());
    }

    public List<SpecialPrice> loadSentCodes(String eventName, int categoryId, String username) {
        final EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, username);
        checkOwnership(categoryId, event, username);
        Predicate<SpecialPrice> p = SpecialPrice::notSent;
        return specialPriceRepository.findAllByCategoryId(categoryId).stream().filter(p.negate()).collect(toList());
    }

    public boolean clearRecipientData(String eventName, int categoryId, int codeId, String username) {
        final EventAndOrganizationId event = eventManager.getEventAndOrganizationId(eventName, username);
        checkOwnership(categoryId, event, username);
        int result = specialPriceRepository.clearRecipientData(codeId, categoryId);
        Validate.isTrue(result <= 1, "too many records affected");
        return result == 1;
    }

    public boolean sendCodeToAssignee(List<SendCodeModification> input, String eventName, int categoryId, String username) {
        final Event event = eventManager.getSingleEvent(eventName, username);
        final Organization organization = eventManager.loadOrganizer(event, username);
        Set<SendCodeModification> set = new LinkedHashSet<>(input);
        checkCodeAssignment(set, categoryId, event, username);
        Validate.isTrue(set.stream().allMatch(IS_CODE_PRESENT), "There are missing codes. Please check input file.");
        List<ContentLanguage> eventLanguages = i18nManager.getEventLanguages(event.getLocales());
        Validate.isTrue(!eventLanguages.isEmpty(), "No locales have been defined for the event. Please check the configuration");
        ContentLanguage defaultLocale = eventLanguages.contains(ContentLanguage.ENGLISH) ? ContentLanguage.ENGLISH : eventLanguages.get(0);
        set.forEach(m -> {
            Locale locale = Locale.forLanguageTag(StringUtils.defaultString(StringUtils.trimToNull(m.getLanguage()), defaultLocale.getLanguage()));
            Map<String, Object> model = TemplateResource.prepareModelForSendReservedCode(organization, event, m, eventManager.getEventUrl(event));
            notificationManager.sendSimpleEmail(event, null, m.getEmail(), messageSource.getMessage("email-code.subject", new Object[] {event.getDisplayName()}, locale), () -> templateManager.renderTemplate(event, TemplateResource.SEND_RESERVED_CODE, model, locale));
            int marked = specialPriceRepository.markAsSent(ZonedDateTime.now(event.getZoneId()), m.getAssignee().trim(), m.getEmail().trim(), m.getCode().trim());
            Validate.isTrue(marked == 1, "Expected exactly one row updated, got "+marked);
        });
        return true;
    }
}

import { describe, expect, it } from "vitest";
import { renderMentions, toMentionMarkup } from "./api";

/**
 * Mention markup is the contract between the composer, the backend's parser and the
 * display layer. All three have to agree, and the id is the part that must survive —
 * it is what decides whose inbox a notification lands in.
 */
describe("mention markup", () => {
  const id = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";

  it("round-trips through the markup the backend parses", () => {
    const markup = toMentionMarkup("Priya Raman", id);
    expect(markup).toBe(`@[Priya Raman](${id})`);

    const { text, mentionIds } = renderMentions(markup);
    expect(text).toBe("@Priya Raman");
    expect(mentionIds).toEqual([id]);
  });

  it("renders the name and never leaks the id into what a person reads", () => {
    const { text } = renderMentions(`Can you look, @[Priya Raman](${id})? Thanks.`);

    expect(text).toBe("Can you look, @Priya Raman? Thanks.");
    expect(text).not.toContain(id);
  });

  it("collects every mention in order", () => {
    const second = "11111111-2222-4333-8444-555555555555";
    const { mentionIds } = renderMentions(
      `@[A](${id}) and @[B](${second}) should both hear about this`,
    );

    expect(mentionIds).toEqual([id, second]);
  });

  it("leaves a bare @name alone", () => {
    // Deliberate: the client cannot know which Priya, and guessing wrong sends
    // someone's work notification to a stranger.
    const { text, mentionIds } = renderMentions("ping @priya about this");

    expect(text).toBe("ping @priya about this");
    expect(mentionIds).toEqual([]);
  });

  it("ignores markup whose id is not a uuid", () => {
    const { text, mentionIds } = renderMentions("@[Nobody](not-a-uuid) said so");

    expect(mentionIds).toEqual([]);
    expect(text).toContain("@[Nobody](not-a-uuid)");
  });

  it("survives a name containing punctuation", () => {
    const { text, mentionIds } = renderMentions(`@[O'Brien, Seán](${id}) reviewed it`);

    expect(text).toBe("@O'Brien, Seán reviewed it");
    expect(mentionIds).toEqual([id]);
  });
});

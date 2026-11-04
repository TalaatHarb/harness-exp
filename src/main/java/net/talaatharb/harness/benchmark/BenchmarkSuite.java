package net.talaatharb.harness.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** JSON benchmark cases use independent storage; turns in a case may share or switch conversations. */
public class BenchmarkSuite {
    public List<Case> cases = new ArrayList<>();

    public static class Case {
        public String id;
        public JsonNode settings;
        public String document;
        public String documentName = "benchmark-document";
        public List<String> approveTools = new ArrayList<>();
        public List<Turn> turns = new ArrayList<>();
    }

    public static class Turn {
        public String prompt;
        public String conversation = "main";
        public String exact;
        public List<String> contains = new ArrayList<>();
        public List<String> excludes = new ArrayList<>();
        public List<String> tools = new ArrayList<>();
        public List<String> savedMemories = new ArrayList<>();
        public Integer memoryCount;
        public List<String> contextExcludes = new ArrayList<>();
        public String status = "COMPLETED";
        /** Non-empty means a human must review quality even if automated checks pass. */
        public String review;
    }
}

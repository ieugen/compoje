(ns compoje.completion
  "Shell completion scripts generated from the CLI definition (cli/cmdspec).

   The script is static: Tab doesn't start compoje. It completes commands,
   flags (global and per command), flag values (engines, resolve-image
   strategies, docker contexts, config paths for --set) and positional
   arguments (stack files, directories). It registers itself for `compoje`
   and, wrapping bb's own completion, for `bb compoje ...`.

   bash; zsh runs the same script through bashcompinit."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ model

(defn parse-flag
  "Names of a lambdaisland/cli flag string and whether it takes an argument:
   \"-s, --set <override>\" -> {:names [\"-s\" \"--set\"] :arg? true}."
  [flag-string]
  (let [parts (map str/trim (str/split flag-string #","))
        arg? (boolean (some #(re-find #"[ =](<[^>]+>|[A-Z][A-Z_-]*)$" %) parts))
        names (mapcat (fn [p]
                        (let [n (first (str/split p #"[ =]"))]
                          (if-let [[_ base] (re-matches #"--\[no-\](.+)" n)]
                            [(str "--" base) (str "--no-" base)]
                            [n])))
                      parts)]
    {:names (vec names) :arg? arg?}))

^:rct/test
(comment
  (parse-flag "-s, --set <override>") ;=> {:names ["-s" "--set"] :arg? true}
  (parse-flag "--[no-]with-registry-auth") ;=> {:names ["--with-registry-auth" "--no-with-registry-auth"] :arg? false}
  (parse-flag "--stack STACK") ;=> {:names ["--stack"] :arg? true}
  (parse-flag "--dry-run") ;=> {:names ["--dry-run"] :arg? false}
  (parse-flag "-v, --verbose") ;=> {:names ["-v" "--verbose"] :arg? false}
  )

(defn- flags-of
  "Parsed flags of a lambdaisland flags vector (flag-string doc-or-map pairs)."
  [flags]
  (mapv (comp parse-flag first) (partition 2 flags)))

(def help-flags [{:names ["-h" "--help"] :arg? false}])

(defn positional-kind
  "How to complete a command's positional argument, from its command string."
  [command-string]
  (condp re-find command-string
    #"<stack-file>" :stack-file
    #"<dir>" :dir
    #"<shell>" :shell
    nil))

^:rct/test
(comment
  (positional-kind "deploy <stack-file>") ;=> :stack-file
  (positional-kind "init <dir>") ;=> :dir
  (positional-kind "version") ;=> nil
  )

(defn model
  "{:global [flag ...] :commands [{:name :flags :positional} ...]} from a
   lambdaisland cmdspec map (as compoje.cli/cmdspec)."
  [cmdspec]
  {:global (into (flags-of (:flags cmdspec)) help-flags)
   :commands (vec (for [[cmd-string target] (partition 2 (:commands cmdspec))]
                    {:name (first (str/split cmd-string #"\s+"))
                     :flags (flags-of (:flags (meta target)))
                     :positional (positional-kind cmd-string)}))})

(def flag-values
  "Completion for flag arguments, by long flag name: a word list, or a
   keyword for a dynamic kind."
  {"--deploy-driver" ["docker-cli" "docker-api" "docker"]
   "--resolve-image" ["always" "changed" "never"]
   "--context" :docker-context
   "--output" :file
   "--set" :config-path})

(def config-paths
  "--set completion: x-compoje paths (the user types =value after them)."
  ["docker.stack" "docker.context" "docker.prune" "docker.resolve-image" "docker.with-registry-auth"
   "docker.driver" "docker.ssh-mode" "docker.allow-insecure-tcp" "render.undefined" "values."])

;; ------------------------------------------------------------------ bash

(defn- words [xs] (str/join " " xs))

(defn- case-arms
  "bash case arms: pattern) body;;"
  [arms]
  (str/join "\n" (for [[patterns body] arms] (str "    " (str/join "|" patterns) ") " body ";;"))))

(defn- all-names [flags] (mapcat :names flags))

(defn- arg-names [flags] (mapcat :names (filter :arg? flags)))

(defn bash-script
  "The bash completion script for `model`."
  [{:keys [global commands]}]
  (let [value-arms (for [[flag kind] flag-values
                         :let [names (or (some #(when (some #{flag} (:names %)) (:names %))
                                               (concat global (mapcat :flags commands)))
                                         [flag])]]
                     [names (case kind
                              :docker-context "COMPREPLY=($(compgen -W \"default $(__compoje_contexts)\" -- \"$cur\"))"
                              :file "__compoje_files"
                              :config-path (str "__compoje_nospace; COMPREPLY=($(compgen -W \"" (words config-paths)
                                                "\" -- \"$cur\"))")
                              (str "COMPREPLY=($(compgen -W \"" (words kind) "\" -- \"$cur\"))"))])
        positional-arms (for [{:keys [name positional]} commands :when positional]
                          [[name] (case positional
                                    :stack-file "__compoje_stack_files"
                                    :dir "__compoje_dirs"
                                    :shell "COMPREPLY=($(compgen -W \"bash zsh\" -- \"$cur\"))")])]
    (str
     "# compoje shell completion, generated by `compoje completion bash` (" "do not edit; regenerate).\n"
     "# Completes `compoje ...` and `bb compoje ...` (other `bb` words go to bb's own completion).\n\n"
     "__compoje_nospace() { command -v compopt >/dev/null 2>&1 && compopt -o nospace 2>/dev/null; }\n\n"
     "__compoje_contexts() {\n"
     "    local f\n"
     "    for f in \"${DOCKER_CONFIG:-$HOME/.docker}\"/contexts/meta/*/meta.json; do\n"
     "        # meta.json has no trailing newline; print each name on its own line\n"
     "        [ -f \"$f\" ] && echo \"$(sed -n 's/.*\"Name\":\"\\([^\"]*\\)\".*/\\1/p' \"$f\")\"\n"
     "    done\n"
     "}\n\n"
     "__compoje_files() {\n"
     "    if typeset -f _filedir >/dev/null 2>&1; then _filedir; return; fi\n"
     "    command -v compopt >/dev/null 2>&1 && compopt -o filenames 2>/dev/null\n"
     "    local IFS=$'\\n'\n"
     "    COMPREPLY=($(compgen -f -- \"$cur\"))\n"
     "}\n\n"
     "__compoje_dirs() {\n"
     "    if typeset -f _filedir >/dev/null 2>&1; then _filedir -d; return; fi\n"
     "    command -v compopt >/dev/null 2>&1 && compopt -o filenames 2>/dev/null\n"
     "    local IFS=$'\\n'\n"
     "    COMPREPLY=($(compgen -d -- \"$cur\"))\n"
     "}\n\n"
     "# stack files: *.yml, *.yaml and directories\n"
     "__compoje_stack_files() {\n"
     "    if typeset -f _filedir >/dev/null 2>&1; then _filedir '@(yml|yaml)'; return; fi\n"
     "    command -v compopt >/dev/null 2>&1 && compopt -o filenames 2>/dev/null\n"
     "    local IFS=$'\\n'\n"
     "    COMPREPLY=($(compgen -f -X '!*.yml' -- \"$cur\") $(compgen -f -X '!*.yaml' -- \"$cur\") $(compgen -d -- \"$cur\"))\n"
     "}\n\n"
     "# does flag $2 take an argument (global, or for command $1)?\n"
     "__compoje_argflag() {\n"
     "    case \"$2\" in\n"
     (case-arms [[(or (seq (arg-names global)) ["--__none__"]) "return 0"]]) "\n"
     "    esac\n"
     "    case \"$1\" in\n"
     (case-arms (for [{:keys [name flags]} commands :when (seq (arg-names flags))]
                  [[name] (str "case \"$2\" in " (str/join "|" (arg-names flags)) ") return 0;; esac")]))
     "\n    esac\n"
     "    return 1\n"
     "}\n\n"
     "__compoje_flags() {\n"
     "    local flags=\"" (words (all-names global)) "\"\n"
     "    case \"$1\" in\n"
     (case-arms (for [{:keys [name flags]} commands]
                  [[name] (str "flags=\"$flags " (words (all-names flags)) "\"")]))
     "\n    esac\n"
     "    echo \"$flags\"\n"
     "}\n\n"
     "# complete words[$1..]: the words after `compoje`\n"
     "__compoje_complete() {\n"
     "    [ -n \"${ZSH_VERSION-}\" ] && setopt localoptions ksharrays\n"
     "    local start=$1 cword=$COMP_CWORD\n"
     "    local -a words=(\"${COMP_WORDS[@]}\")\n"
     "    local cur=\"${words[cword]}\" prev=\"\" cmd=\"\" skip=\"\" npos=0 i w\n"
     "    (( cword > 0 )) && prev=\"${words[cword-1]}\"\n"
     "    COMPREPLY=()\n"
     "    for (( i=start; i<cword; i++ )); do\n"
     "        w=\"${words[i]}\"\n"
     "        if [ -n \"$skip\" ]; then skip=\"\"; continue; fi\n"
     "        if __compoje_argflag \"$cmd\" \"$w\"; then skip=1; continue; fi\n"
     "        case \"$w\" in\n"
     "            -*) ;;\n"
     "            *) if [ -z \"$cmd\" ]; then\n"
     "                   case \"$w\" in " (str/join "|" (map :name commands)) ") cmd=\"$w\";; esac\n"
     "               else npos=$((npos+1)); fi;;\n"
     "        esac\n"
     "    done\n"
     "    if [ -n \"$skip\" ]; then\n"
     "        case \"$prev\" in\n"
     (case-arms value-arms) "\n"
     "        esac\n"
     "        return 0\n"
     "    fi\n"
     "    case \"$cur\" in\n"
     "        -*) COMPREPLY=($(compgen -W \"$(__compoje_flags \"$cmd\")\" -- \"$cur\")); return 0;;\n"
     "    esac\n"
     "    if [ -z \"$cmd\" ]; then\n"
     "        COMPREPLY=($(compgen -W \"" (words (map :name commands)) "\" -- \"$cur\"))\n"
     "        return 0\n"
     "    fi\n"
     "    [ \"$npos\" -gt 0 ] && return 0\n"
     "    case \"$cmd\" in\n"
     (case-arms positional-arms) "\n"
     "    esac\n"
     "    return 0\n"
     "}\n\n"
     "_compoje() { __compoje_complete 1; }\n"
     "complete -F _compoje compoje\n\n"
     "# `bb compoje ...`: wrap bb's own completion (task names) and handle words after `compoje`.\n"
     "if [ -n \"${BASH_VERSION-}\" ]; then\n"
     "    __compoje_bb_prev_of() {\n"
     "        local spec\n"
     "        spec=$(complete -p bb 2>/dev/null) || return 1\n"
     "        [[ $spec =~ -F\\ ([^ ]+) ]] && [ \"${BASH_REMATCH[1]}\" != _compoje_bb ] && echo \"${BASH_REMATCH[1]}\"\n"
     "    }\n"
     "    _compoje_bb() {\n"
     "        if [ \"${COMP_WORDS[1]}\" = compoje ] && [ \"$COMP_CWORD\" -ge 2 ]; then\n"
     "            __compoje_complete 2\n"
     "            return 0\n"
     "        fi\n"
     "        if [ -z \"${__compoje_bb_prev-}\" ]; then\n"
     "            # first use: let bash-completion load bb's own completion, remember it, take over again\n"
     "            __compoje_bb_prev=none\n"
     "            complete -r bb 2>/dev/null\n"
     "            typeset -f __load_completion >/dev/null 2>&1 && __load_completion bb >/dev/null 2>&1\n"
     "            __compoje_bb_prev=$(__compoje_bb_prev_of) || __compoje_bb_prev=none\n"
     "            [ -n \"$__compoje_bb_prev\" ] || __compoje_bb_prev=none\n"
     "            complete -F _compoje_bb bb\n"
     "        fi\n"
     "        [ \"$__compoje_bb_prev\" != none ] && \"$__compoje_bb_prev\" \"$@\"\n"
     "        return 0\n"
     "    }\n"
     "    # a bb completion registered before this script: keep it\n"
     "    [ -z \"${__compoje_bb_prev-}\" ] && __compoje_bb_prev=$(__compoje_bb_prev_of)\n"
     "    [ -n \"$__compoje_bb_prev\" ] || unset __compoje_bb_prev\n"
     "    complete -F _compoje_bb bb\n"
     "fi\n")))

(defn zsh-script
  "zsh: the bash script through bashcompinit."
  [model]
  (str "# compoje zsh completion: the bash script through bashcompinit.\n"
       "autoload -U +X bashcompinit && bashcompinit\n\n"
       (bash-script model)))

# Reads scripts/deploy.local.env as data, for deploy-relay.sh and deploy-bot.sh.
# Sourced, never run. The file holds the operator's private deployment settings
# (git-ignored); it used to be sourced as shell, so a `$(...)` in any value ran
# on the operator's machine. Now:
#   - it must be owned by the user and not readable or writable by group or
#     others (chmod 600), or the deploy stops before anything remote happens;
#   - only lines `US2US_NAME=value` (optionally `export ` before) or
#     `: "${US2US_NAME:=value}"` are read;
#     other names and other lines are ignored with a warning (no value is
#     printed); a value is taken literally: no command substitution, no
#     variables, except a leading `~/`, `$HOME/` or `${HOME}/`;
#   - one pair of matching quotes around a value is removed;
#   - a setting already in the environment wins over the file.
# shellcheck shell=bash

us2us_read_local_env() {
  local file="$1" mode owner line number=0 key value
  [ -f "$file" ] || return 0
  if [ -L "$file" ]; then echo "$file must be a regular file, not a symlink" >&2; exit 2; fi
  read -r mode owner < <(node -e 'const s=require("node:fs").statSync(process.argv[1]);process.stdout.write(`${(s.mode&0o777).toString(8)} ${s.uid}\n`)' "$file")
  if [ "$owner" != "$(id -u)" ] || (( 8#$mode & 8#077 )); then
    echo "$file must belong to you and be private: chmod 600 \"$file\"" >&2
    exit 2
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    number=$((number + 1))
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#.*)?$ ]] && continue
    if [[ "$line" =~ ^[[:space:]]*:[[:space:]]+\"?\$\{(US2US_[A-Z0-9_]+):?=([^}]*)\}\"?[[:space:]]*$ ]]; then
      # The shell-default form `: "${US2US_NAME:=value}"`, as the file was first written.
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
    elif [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?(US2US_[A-Z0-9_]+)=(.*)$ ]]; then
      key="${BASH_REMATCH[2]}"
      value="${BASH_REMATCH[3]}"
    else
      echo "warning: $file line $number is not a US2US_NAME=value setting; ignored" >&2
      continue
    fi
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then value="${BASH_REMATCH[1]}"; fi
    case "$value" in
      '~/'*) value="$HOME/${value#'~/'}" ;;
      '$HOME/'*) value="$HOME/${value#'$HOME/'}" ;;
      '${HOME}/'*) value="$HOME/${value#'${HOME}/'}" ;;
    esac
    [ -n "${!key+set}" ] && continue
    printf -v "$key" '%s' "$value"
    export "${key?}"
  done < "$file"
}
